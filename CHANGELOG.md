# Version 1.0 (6/4/2015)

+ Issue #5 fixed (error when building/running in lollipop) - thanks brendanclement!
+ Fixed crash if device lacks bluetooth capability


# Version 0.9 (5/25/2015)

+ Added action type for switching to last app (like ALT+TAB on PC), only works PRE Lollipop, settings example: *LAST_APP 
+ Increased android:versionCode (issue #2)
+ Modified setting in gradle.build to work with latest version of gradle (2.2.1)


# Version 0.8 (8/21/2014)

+	Re-organized project including a lot of renaming. The package name changed as well users of previous versions will end up with double installs. Simply remove the older version using Settings > Apps.
+	Added a debug terminal to allow basic interaction with the ELM327 interface device (monitor bus messages, send AT commands). The option is at the bottom of the settings screen.
+	The app no longer hides from the App Switcher or Recent Apps Dialog. This allows easily switching back to the terminal screen. The app still launches without any UI (other than the system notification).
+	Fixed threading bugs that prevented correct application flow or caused hanging in some cases. 
+	Other minor bug fixes and general refactoring to improve code quality.
+	KNOWN ISSUE: Within the debug terminal, issuing commands that result in a large flood of messages cause the UI to become unresponsive (such as "ATMA" on a very active bus).
+	KNOWN ISSUE: Occasionally the app will indicate that it is connected though in reality there is no communication with the ELM327 interface. This seems to only occur on Android 4.1 systems. Turning bluetooth completely OFF then back ON resolves the issue.


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
