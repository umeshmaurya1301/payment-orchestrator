import com.google.protobuf.gradle.id

plugins {
    // java-library for `api`: every service on an internal hop needs the
    // generated stubs on its COMPILE classpath, not just at runtime, so they
    // have to be exported rather than hidden behind an implementation edge. The
    // root build applies plain `java` to every subproject, which gives
    // `implementation` and not `api`.
    `java-library`
    alias(libs.plugins.protobuf)
}

description = "Protobuf contracts and generated gRPC stubs. Phase 9a."

// NOT a Spring Boot module, and it must not become one. The root build applies
// the Boot plugin conditionally (see build.gradle.kts); a module whose only job
// is generated code has no application to package, and a bootJar of it would be
// an empty executable jar that something would eventually try to run.

dependencies {
    api(libs.protobuf.java)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)

    // The generated stubs are annotated @javax.annotation.Generated, which was
    // removed from the JDK in Java 11 and has not come back. Without this the
    // generated sources do not compile, with an error that names a class nobody
    // wrote and no file anybody can find.
    compileOnly(libs.tomcat.annotations)
    api(libs.tomcat.annotations)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        id("grpc") {
            artifact = libs.grpc.gen.java.get().toString()
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}
