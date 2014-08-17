# Version 0.8 (in progress)

+	Reorganized project including lots of renaming. Package name changed so users will end up with double installs, just remove the older version using Settings > Apps.
+	Added debug terminal to allow basic interaction with the ELM327 interface device (monitor bus messages, send AT commands). The option is at the bottom of the settings screen.
+	Application no longer hides from the app switcher or recents dialog. This was done to allow easily switching back to the terminal screen without it resetting. The app still launches without a UI, only showing the terminal or settings screen when returning to app or tapping on the system notification.
+	Threading bug fixes preventing correct application flow or causing hanging in some cases. 
+	Other minor bug fixes and general refactoring to improve code quality.


# Version 0.7 (8/12/2014)

+	Updated help link to point to the new project Wiki
+	Documentation updates


# Version 0.6 (8/11/2014)

+	Added action type for sending a basic implicit intent, example: *INTENT=android.intent.action.VIEW**http://google.com
+	Added action type for executing a Tasker task, example: *TASKER=Play A Playlist**Dubstep Favorites


# Version 0.5 (8/11/2014)

+	Fixed logic error preventing connections


# Version 0.4 (8/10/2014)

+	Initial public release
