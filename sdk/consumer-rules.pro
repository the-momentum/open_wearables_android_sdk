# WorkManager stores its state in a Room database (androidx.work.impl.WorkDatabase).
# Room creates the generated WorkDatabase_Impl reflectively through its no-arg
# constructor. Room 2.5/2.6 ship "-keep class * extends androidx.room.RoomDatabase"
# without a member spec. R8 9.0 (AGP 9) then removes that constructor and the app
# crashes at startup in androidx.startup.InitializationProvider. Room 2.7+ keeps
# the constructor itself; these rules also cover apps whose dependency graph
# resolves an older Room.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.room.Room { *; }
