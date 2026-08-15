description = "The token vault: PAN tokenization at the edge and the single audited detokenization path."

dependencies {
    // Masking.isLuhnValid and the @Sensitive vocabulary. Tokenization and
    // masking are two layers of the same control, so the coupling is real.
    api(project(":logging-starter"))

    // The JDBC libraries, not spring-boot-starter-jdbc. Pulling the starter in
    // would hand every consumer DataSourceAutoConfiguration as well, and the
    // vault connection is deliberately built outside that machinery - see
    // VaultConnection.
    compileOnly(libs.spring.jdbc)
    compileOnly(libs.hikaricp)
    testImplementation(libs.spring.jdbc)
    testImplementation(libs.hikaricp)
    testImplementation(libs.h2)
}
