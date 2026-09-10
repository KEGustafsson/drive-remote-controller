package io.github.kegustafsson.driveremote.core

/**
 * Has the access token stopped being accepted?
 *
 * The naive rule -- "a 401 means the token is dead" -- is wrong here, and
 * wrong in the expensive direction: it would throw away a working token and
 * send the operator back through the access-request flow in the middle of a
 * manoeuvre.
 *
 * Two things produce a 401 that is not a dead token:
 *
 *  1. **The auth-scheme probe.** signalk-server has historically accepted only
 *     `JWT <token>` on some versions and `Bearer <token>` on others (issue
 *     #715), so the clients try [AuthScheme.TRY_ORDER] in turn. Every scheme
 *     before the right one is rejected, by design. Those rejections are the
 *     handshake working, not failing.
 *  2. **A server restarting.** A server that is part-way up can reject briefly
 *     and then accept.
 *
 * So the token is declared dead only when BOTH hold:
 *
 *  - there have been [REJECTIONS_BEFORE_DEAD] *consecutive* rejections -- one
 *    per scheme the client will try, plus one more so that a single rejection
 *    after the probe has settled is not enough; and
 *  - **every** scheme in [AuthScheme.TRY_ORDER] has actually been refused.
 *
 * The second condition is what stops a count alone from lying. Rejections are
 * fed in from concurrent senders -- the 250 ms heartbeat, a hurried STOP, a
 * backgrounding release -- and STOP deliberately does not queue behind the
 * others, because disarm is never gated. So on a `JWT`-only server three sends
 * can be built with the same unprobed `Bearer` scheme before any response comes
 * back to advance it, and their three 401s would otherwise spend the whole
 * budget on what is really ONE failed probe: a working token discarded and the
 * controls taken away, at the exact moment the operator was reaching for STOP.
 * Counting schemes rather than replies makes the verdict independent of how
 * many requests happen to be in flight.
 *
 * At the 250 ms intent heartbeat the verdict still lands in well under a
 * second, which is quick enough that the operator is not left commanding into a
 * server that has stopped listening.
 *
 * A single acceptance clears everything: the question is whether the token
 * works *now*, not how many times it has ever been refused.
 *
 * This is deliberately about the token only. A transport failure -- no route
 * to the server, socket dropped -- is NOT a rejection and must not be fed in
 * here. Losing the network is not losing authority, and treating it as such
 * would drop a good token every time the boat's wifi hiccuped.
 */
data class TokenHealth(
  val consecutiveRejections: Int = 0,
  /** Which schemes have been refused since the last acceptance. */
  val schemesRefused: Set<AuthScheme> = emptySet(),
) {

  /** The server accepted a request. Whatever came before no longer matters. */
  fun accepted(): TokenHealth = if (this == HEALTHY) this else HEALTHY

  /**
   * The server answered 401/403 to a request sent with [scheme].
   *
   * The scheme is required rather than optional: a rejection whose scheme is
   * unknown cannot be told apart from an unfinished probe, and defaulting it
   * either way would quietly restore the bug this parameter exists to close.
   */
  fun rejected(scheme: AuthScheme): TokenHealth =
    TokenHealth(consecutiveRejections + 1, schemesRefused + scheme)

  /**
   * Is the token past saving? True means: stop commanding, forget it, and send
   * the operator back to get a new one.
   */
  val isDead: Boolean
    get() =
      consecutiveRejections >= REJECTIONS_BEFORE_DEAD &&
        schemesRefused.containsAll(AuthScheme.TRY_ORDER)

  companion object {
    /** A token nothing has refused. */
    val HEALTHY = TokenHealth()

    /**
     * One rejection for each scheme the client probes, plus one.
     *
     * Derived from [AuthScheme.TRY_ORDER] rather than hard-coded, so adding a
     * third scheme cannot silently make the probe itself look like a dead
     * token -- the kind of coupling that only shows up on the water.
     */
    val REJECTIONS_BEFORE_DEAD: Int = AuthScheme.TRY_ORDER.size + 1
  }
}
