## Developing

Start the server:

    $ sbt
    > ~reStart run --- -Dakka.minion.api-key=<github_api_key> -Dakka.minion.poll-interval=10h

This will start the app and configure it to not poll GitHub after the initial poll. This will save API quota while developing the application. To stop it run:

    > reStop

## Deploying

You need to be a collaborator of [akka-minion in Heroku](https://dashboard.heroku.com/apps/akka-minion) to have deploying access. Ask @2m to become one.

Then push to the following git remote:

    https://git.heroku.com/akka-minion.git
