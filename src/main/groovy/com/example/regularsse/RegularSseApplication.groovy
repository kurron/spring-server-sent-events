package com.example.regularsse

import groovy.util.logging.Slf4j
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@SpringBootApplication
@RestController
@EnableScheduling
@Slf4j
class RegularSseApplication {

    /**
     * Number of milliseconds to hold the connection open, waiting for updates.
     */
    public static final int HOW_LONG_THE_CLIENT_WILL_WAIT_FOR_UPDATES = 60 * 1000

    /**
     * Simulate an order pipeline.
     */
    final static stages = ['Accepted',
                           'Inventory Confirmed',
                           'Payment Confirmed',
                           'Out to shipping',
                           'In Transit',
                           'Delivered',
                           'Completed']

    /**
     * The collection of clients that want order update notifications.
     */
    final static Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>( 8 )

    /**
     * The producers and consumers of updates exchange data here.
     */
    final static BlockingQueue<Integer> rendezvous = new LinkedBlockingQueue<>( 8 )

    /**
     * Thread-safe collection of indexes into the order's current stage.
     */
    final static List<AtomicInteger> currentStage = ( 1..4 ).collect { new AtomicInteger( 0 ) }

    /**
     * Maps orders to interested parties.
     */
    final static Map<Integer, List<UUID>> watching = new ConcurrentHashMap<>( 8 )

    /**
     * Subscriber calls this, providing the order number of interest, and will leave the connection
     * open for a period of time, during which any stage transitions will be sent.  Once the order
     * has fully progressed through its lifecycle, no waiting will take place.
     * @param orderID the order to wait on for updates, e.g. 1
     * @return emitter that manages updates back to the subscriber.
     */
    @GetMapping( '/subscribe/{orderID}' )
    SseEmitter subscribe( @PathVariable Integer orderID ) {
        // every subscriber gets a dedicated emitter
        def subscriberID = UUID.randomUUID()
        log.info( 'Subscriber {} is interested in order {}', subscriberID, orderID )
        def emitter = createEmitter( subscriberID, orderID, emitters, watching )
        def stage = currentStage( orderID )
        sendUpdate( emitter, orderID, stage )
        emitter
    }

    /**
     * Creates a new emitter for the subscriber, managing the various look up tables.
     * @param subscriber ID of the subscriber we are creating the emitter for.
     * @param orderID the order the subscriber is interested in.
     * @param emitters mapping of subscribers to emitters.
     * @param watching mapping of orders to interested subscribers.
     * @return fully assembled emitter.
     */
    private static SseEmitter createEmitter( UUID subscriber,
                                             Integer orderID,
                                             Map<UUID, SseEmitter> emitters,
                                             Map<Integer, List<UUID>> watching ) {
        def emitter = new SseEmitter( HOW_LONG_THE_CLIENT_WILL_WAIT_FOR_UPDATES )
        updateSubscriberToEmitterMappings( emitters, subscriber, emitter )
        updateOrderToSubscriberMapping( watching, orderID, subscriber )
        installLifecycleCallbacks( watching, orderID, subscriber, emitters, emitter )
        emitter
    }

    /**
     * Adds in the necessary callbacks to clean up resources when connections get closed.
     * @param watching mapping of orders to interested subscribers.
     * @param orderID the order the subscriber is interested in.
     * @param subscriber ID of the subscriber we are creating the emitter for.
     * @param emitters mapping of subscribers to emitters.
     * @param emitter emitter to configure.
     */
    private static void installLifecycleCallbacks( Map<Integer, List<UUID>> watching,
                                                   Integer orderID,
                                                   UUID subscriber,
                                                   Map<UUID, SseEmitter> emitters,
                                                   SseEmitter emitter ) {
        def callback = {
            log.info( 'Cleaning up resources for subscriber {}.', subscriber )
            watching.get( orderID ).remove( subscriber )
            emitters.remove( subscriber )
        }
        emitter.onCompletion( callback )
        emitter.onTimeout( callback )
    }

    /**
     * Add the mapping of subscriber to emitter.
     * @param emitters mapping of subscribers to emitters.
     * @param subscriber ID of the subscriber we are creating the emitter for.
     * @param emitter emitter to map.
     */
    private static void updateSubscriberToEmitterMappings( Map<UUID, SseEmitter> emitters,
                                                           UUID subscriber,
                                                           SseEmitter emitter ) {
        emitters.put( subscriber, emitter )
    }

    /**
     * Add the mapping of orders to subscribers.
     * @param watching mapping of orders to interested subscribers.
     * @param orderID the order the subscriber is interested in.
     * @param subscriber ID of the subscriber we are creating the emitter for.
     */
    private static void updateOrderToSubscriberMapping( Map<Integer, List<UUID>> watching,
                                                        int orderID,
                                                        UUID subscriber ) {
        watching.putIfAbsent( orderID.toInteger(), [] )
        watching.get( orderID.toInteger() ).add( subscriber )
    }

    /**
     * Simulates an order progressing through the processing pipeline.
     */
    @Scheduled( fixedRate = 4000L )
    static void simulateProgress() {
        def orderID = ThreadLocalRandom.current().nextInt( currentStage.size() )
        transitionOrder( orderID )
        submitUpdate( orderID )
    }

    /**
     * Transitions the specified order to the next stage.
     * @param orderID ID of the order to transition.
     */
    private static void transitionOrder( int orderID ) {
        def toTransition = currentStage[orderID]
        log.info( 'Progressing order {} to next stage', orderID )
        def index = toTransition.get() < stages.size() - 1 ? toTransition.incrementAndGet() : toTransition.get()
        log.info( 'Order {} is currently in the {} stage', orderID, stages[index] )
    }

    /**
     * Places the transitioned order into the work queue so it can be pushed to the subscribers.
     * @param orderID ID of the order that has changed.
     */
    private static void submitUpdate( int orderID ) {
        def successful = rendezvous.offer( orderID )
        log.info( 'Order {} {} been placed in the rendezvous queue', orderID, successful ? 'has' : 'has not' )
    }

    /**
     * Notify the subscriber that an order's stage has changed.
     * @param emitter subscriber to notify.
     * @param orderID order that has changed.
     * @param stage the order's current state.
     */
    private static void sendUpdate( SseEmitter emitter, int orderID, String stage ) {
        def builder = SseEmitter.event()
                                .name( "Update on order ${orderID}" )
                                .id( UUID.randomUUID() as String )
                                .data( stage, MediaType.TEXT_PLAIN )
        emitter.send( builder )
        if ( 'Completed' == stage ) {
            log.info( 'Updates on order {} are complete', orderID )
            emitter.complete()
        }
    }

    /**
     * Obtain the order's current state.
     * @param orderID order to look up.
     * @return found stage.
     */
    private static String currentStage( int orderID ) {
        stages.get( currentStage.get( orderID ).get() )
    }

    /**
     * Drives the program.
     * @param args command-line arguments.
     */
    static void main( String[] args ) {
        SpringApplication.run RegularSseApplication, args
        log.info( 'Started on {}', Thread.currentThread().name )
        def changeHandler = {
            while ( true ) {
                def orderID = rendezvous.take()
                log.info( 'Order {} has transitioned', orderID )
                def interested = watching.getOrDefault( orderID, [] )
                interested.each { subscriberID ->
                    def emitter = emitters.get( subscriberID )
                    log.info( 'Updating subscriber {} on order {}', subscriberID, orderID )
                    def stage = currentStage( orderID )
                    sendUpdate( emitter, orderID, stage )
                }
            }
        } as Runnable
        new Thread( changeHandler ).start()
    }

}
