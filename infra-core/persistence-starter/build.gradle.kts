description = "UUIDv7 identifiers and their BINARY(16) representation. Shared by every service that owns a table."

dependencies {
    // `api`: consumers call Uuid7 directly, and the generator type leaks into
    // their signatures.
    api(libs.uuid.creator)

    // No JPA dependency, deliberately. An AttributeConverter would be the
    // obvious thing to ship here and Hibernate 7 rejects one on an @Id field
    // outright - see the note in Uuid7. Native UUID mapping does the job, so
    // this module stays a plain identifier library that services without a
    // persistence provider can also use.
}
