-- Phase 5d. Which routing strategy each merchant gets.
--
-- WHY THIS IS A MERCHANT COLUMN AND NOT A GLOBAL SETTING
--
-- Merchants buy different things. One wants the highest authorization rate and
-- will pay for it; another sells low-margin goods and wants the cheapest
-- provider that clears a health floor; a third has a commercial agreement that
-- says a named provider gets first refusal. A single global switch cannot
-- express any of that, and "routing strategy" stops being infrastructure and
-- becomes a product feature the moment it is per merchant.
--
-- The strategies themselves are described in RoutingStrategy. In summary:
--
--   HEALTH_WEIGHTED  the phase-5b default: priority rank decayed, health
--                    squared, weighted random. Spreads load, keeps every
--                    provider's signal fresh, degrades gracefully.
--   LEAST_LATENCY    lowest rolling P99 among providers above the health floor.
--                    For merchants where checkout abandonment is the cost.
--   CHEAPEST         lowest cost_bps that clears the health floor. The health
--                    floor is the whole point - "cheapest" without one routes
--                    to whichever provider is failing most cheaply.
--   PRIORITY         strictly the first routable provider by priority. Phase 1's
--                    behaviour, kept as a named strategy rather than as a
--                    fallback, because a merchant with a commercial agreement
--                    needs it to be a choice somebody made.
--
-- NOT AN ENUM COLUMN. MySQL enums require an ALTER TABLE to add a value, which
-- means a schema migration to ship a new strategy, which means the strategy set
-- is coupled to the deploy. A varchar with an unrecognised value falls back to
-- HEALTH_WEIGHTED and logs - see RoutingStrategy.parse.
ALTER TABLE merchant
    ADD COLUMN routing_strategy VARCHAR(32) NOT NULL DEFAULT 'HEALTH_WEIGHTED';

-- Cost, in basis points of the transaction amount.
--
-- On psp_config rather than anywhere else because it is a property of the
-- provider's contract, and it belongs beside the other contract terms - the TPS
-- ceiling and the latency budget - that 3f already made changeable at runtime.
--
-- Basis points rather than a decimal rate: integer arithmetic, no rounding
-- surprises when comparing two providers, and the unit acquirers actually quote.
ALTER TABLE psp_config
    ADD COLUMN cost_bps INT NOT NULL DEFAULT 200;

-- Deliberately spread so CHEAPEST and LEAST_LATENCY disagree with each other and
-- with priority. A demonstration where every strategy picks the same provider
-- demonstrates nothing.
--
--   psp-a  fast (200ms), reliable, EXPENSIVE   - LEAST_LATENCY picks it
--   psp-b  slow (2.5s),  4% declines, CHEAPEST - CHEAPEST picks it
--   psp-c  middling on both                    - the compromise
--   mockpsp the phase-1 provider, mid-priced
UPDATE psp_config SET cost_bps = 275 WHERE psp_id = 'psp-a';
UPDATE psp_config SET cost_bps = 120 WHERE psp_id = 'psp-b';
UPDATE psp_config SET cost_bps = 195 WHERE psp_id = 'psp-c';
UPDATE psp_config SET cost_bps = 200 WHERE psp_id = 'mockpsp';
