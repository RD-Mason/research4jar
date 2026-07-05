plugins {
    base
    // Declared here (apply false) so sibling modules share one classloader
    // scope for these plugins (vanniktech's build service requires it and
    // needs the Kotlin plugin classes visible alongside it); each module
    // applies what it uses itself.
    kotlin("jvm") version "2.1.21" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}
