## Troubleshooting

If you get a gradle problem with the dependency, check if the problem is related
to IntelliJ (i.e. try './gradlew build'). If the problem only occurs in
IntelliJ, check gradle JVM in settings (or perhaps switch to openjdk 15 for the
whole project). Also check that you have configured the project to use Java 17 or later.
