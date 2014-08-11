# Car Bus Interface

Android application for interfacing with a vehicle's communication bus via Bluetooth OBD2 dongle. It allows the Android device to respond to specific bus messages, such as those occurring when the user presses radio controls buttons.

This is an evolution of the [Steering Wheel Interface app](https://github.com/theksmith/Steering-Wheel-Interface), written to be more configurable and for Bluetooth instead of USB.

## Purpose

The primary use case is for those with an Android based tablet acting as a [carputer](http://en.wikipedia.org/wiki/Carputer) who wish to have their steering wheel mounted factory stereo buttons control the Android device (adjust volume, change media tracks, etc). However, you could have the Android device respond to any message that is broadcast on the vehicle's bus - from a window roll down event to haven reached engine operating temperature.

This is NOT an OBDII or "code reader" type application. An OBDII dongle is used only to provide a hardware interface to non-diagnostic vehicle buses which use standard OBDII protocols (SAE J1850 PWM, SAE J1850 VPW, ISO 9141-2, ISO 14230-4 KWP, ISO 15765-4 CAN, SAE J1939 CAN).

## Getting Started

DISCLAIMER: EVERYTHING INCLUDED IN THIS REPOSITORY IS FOR INFORMATION PURPOSES ONLY. I AM NOT RESPONSIBLE FOR ANY DAMAGE TO VECHILES, DEVICES OR YOURSELF. DO NOT ATTEMPT ANY OF THE FOLLOWING UNLESS YOU KNOW WHAT YOU ARE DOING.

### Testing the application (as-is):

Requirements:

+	An Android device with Bluetooth support running Android 4.1 or newer. Most of the code could be ported to earlier versions of Android without much work. Root access is required for some functionality.

+	An inexpensive ELM327 based Bluetooth OBDII scantool. Note that many of the cheapest eBay clones do not implement every protocol correctly. [This one](http://amzn.to/1oCmW0X) is known to work well.

+	A compatible vehicle. As it sits, the app will work on many Jeep/Chrysler/Dodge vehicles from the late 90's through mid 2000's - specifically those with a SAE J1850 VPW base PCI Bus. It could also work with nearly any modern vehicle with some adjustments to the configuration (see the next section).

Verify you have paired your OBD2 adapter with your android device correctly. Install and launch the app. There will be notification in the Android status bar with a key icon. Tap this notification to go to the app's settings screen. Select your device under the "OBD2 Interface Device" setting and then tap the "Apply Changes & Restart App" option. The notification will say "Connected to (your adapter)" once it is ready to test.

In my 2003 Jeep Grand Cherokee, the default configuration works with the factory steering wheel mounted stereo buttons as follows:

+	Right UP button: Volume increase
+	Right DOWN button: Volume decrease
+	Right CENTER button (single quick press): Shows an alert with text "Single press"
+	Right CENTER button (quick double-tap): Shows an alert with text "Double-tap"

+	Left UP button: Next track on currently focused media player (requires root)
+	Left DOWN button: Previous track (requires root)
+	Left CENTER button: Pause/Play (requires root)

### Changing the car bus message monitors & responses

Documentation coming soon...

### To use with other vehicles:

1.	Determine how to interface with the *Comfort Bus* or similar in your vehicle. This could be as simple as setting the correct protocol or could involve modifications to the scantool or vehicle wiring.

	Many older vehicles have their buses interconnected thereby allowing you to use the OBDII port to talk to any bus. However, most newer vehicles isolate the *Diagnostic Bus* (on the OBDII port). For those vehicles you might have to splice into another bus directly. Sometimes extra pins on the OBDII port will expose the additional buses. The radio harness typically exposes the *Comfort Bus* on some pin. Get a factory service manual with wiring diagrams or Google and hope someone else has hacked on your specific vehicle year/make/model.

	See [this article](http://theksmith.com/technology/hack-vehicle-bus-cheap-easy-part-1/) for more info.

2.	Determine what bus messages are sent when interacting with the factory device you wish to monitor.
	
	See [this article](http://theksmith.com/technology/hack-vehicle-bus-cheap-easy-part-2/) for an example how-to.

3.	Adjust the app's settings as needed based on your findings.

	If all messages that you wish to monitor for are sent from or to a particular device id, use **ATMT##** or **ATMR##** for the monitor command. See the [ELM327 datasheet](http://www.elmelectronics.com/DSheets/ELM327DS.pdf) for more info. You could use **ATMA** instead to monitor all bus messages, though this could affect performance. For newer CAN BUS based vehicles, you will likely need to enter some advanced filtering commands.

## Copyright and License

Copyright 2013 Kristoffer Smith and other contributors.

This project is licensed under the [MIT License](http://opensource.org/licenses/MIT).

## Ongoing Development

+	Pull requests are welcome! Please contact me if you'd like to do a major re-work or extension of the project.

	https://github.com/theksmith/CarBusInterface

	I'd really like to implement improved ways to interact with the entire system on *non-rooted* devices (go to the Home screen, pause any media player, etc.).

+	The source project is from [Android Studio Beta 0.8.2](https://developer.android.com/sdk/installing/studio.html) but the code should move to Eclipse easily if you prefer that IDE.

+	No third party libraries are required.

## Credits

### Authors

+	Kristoffer Smith ([http://theksmith.com/](http://theksmith.com/))

### Contributors

+	None yet

### Acknowledgments

+	CarBusInterfaceService.java was inspired by the Android SDK's BluetoothChatService.java sample file. 
