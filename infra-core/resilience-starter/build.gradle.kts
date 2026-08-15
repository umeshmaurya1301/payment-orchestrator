description = "Deadline budget, retry, circuit breaker, bulkhead, rate limiters. Filled in during phase 3."

// Deliberately no resilience4j dependency yet. Phase 3 adds each component only
// after phase 2 has measured the failure it is supposed to prevent - the
// before/after graph is the point, and a component added early has no "before".
dependencies {
}
