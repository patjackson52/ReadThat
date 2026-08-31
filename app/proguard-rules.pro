# ReadThat-specific R8 rules.
#
# Compose, Room, Kotlin serialization, Coil, Ktor/Cronet, Media3, and WorkManager publish the
# consumer rules needed by their runtime implementations. Keep this file narrow so release builds
# retain R8's whole-program optimization instead of broadly preserving application classes.
