## Developing

Start the server:

    $ sbt
    > ~reStart run --- -Dakka.minion.api-key=<github_api_key> -Dakka.minion.poll-interval=10h

This will start the app and configure it to not poll GitHub after the initial poll. This will save API quota while developing the application. To stop it run:

    > reStop
