# Overview
This project is a sample that helps to showcase how Spring supports Server-Sent Events.  The application simulates an on-line ordering system, allowing HTTP clients to subscribe to changes in orders as they progress through the processing pipeline.  The program knows about orders 0-3 which clients can subscribe to, getting any changes as they occur.

# Guidebook
Details about this project are contained in the [guidebook](guidebook/guidebook.md)
and should be considered mandatory reading prior to contributing to this project.

# Prerequisites
* [JDK 8](http://zulu.org/) installed and working
* [cURL](https://curl.haxx.se/) for testing

# Building
`./gradlew` will pull down any dependencies, compile the source and package everything up.

# Installation
Nothing to install.

# Tips and Tricks
## Starting The Server
`./gradlew bootRun` will start the server on port `8080` and begin transitioning the orders.  Every 4 seconds an order is randomly selected and transitioned to the next stage.  Eventually, all orders will end in the `Completed` state.

## Subscribing
While the server is running, run `curl localhost:8080/subscribe/0` which will have the client wait one minute for any updates to order `0`.  You can also run a second instance of cURL, which will get the same updates.  If the order has already transitioned to `Complete`, no updates are coming so the connection is closed.

## Cleanup
The connection to the subscriber will terminate due to one of two reasons.  One, the transitions are complete so no more updates are forthcoming. Second, the client is done waiting and disappears.  In either case, callbacks are invoked that clean up the various lookup tables.  

# Troubleshooting

# Contributing

# License and Credits
* This project is licensed under the [Apache License Version 2.0, January 2004](http://www.apache.org/licenses/).
* The guidebook structure was created by [Simon Brown](http://simonbrown.je/) as part of his work on the [C4 Architectural Model](https://c4model.com/).  His books can be [purchased from LeanPub](https://leanpub.com/b/software-architecture).
* Patrick Kua offered [his thoughts on a travel guide to a software system](https://www.safaribooksonline.com/library/view/oreilly-software-architecture/9781491985274/video315451.html) which has been [captured in this template](travel-guide/travel-guide.md).

# List of Changes
