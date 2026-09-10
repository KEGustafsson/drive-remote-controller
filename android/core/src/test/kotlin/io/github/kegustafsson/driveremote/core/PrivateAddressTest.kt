package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cleartext gate. Android's network security config cannot express IP
 * ranges, so this is the only place the "private networks only" rule is
 * actually enforced -- which makes these cases the whole of its coverage.
 *
 * Every unrecognised form must come out FALSE (refuse cleartext), because the
 * consequence of a wrong "true" is a Signal K token that authorises commanding
 * machinery going out in the clear to a public host.
 */
class PrivateAddressTest {

  // ---- The case that was actually broken ---------------------------------

  @Test
  fun `the boat server address is private`() {
    // The exact address that failed with "CLEARTEXT communication to
    // 192.168.0.100 not permitted by network security policy".
    assertTrue(isPrivateHost("192.168.0.100"))
    assertTrue(ServerAddress("192.168.0.100", 3000).isCleartextSafe)
  }

  // ---- RFC1918 and friends -----------------------------------------------

  @Test
  fun `accepts every private IPv4 range`() {
    assertTrue(isPrivateHost("10.0.0.1"))
    assertTrue(isPrivateHost("10.255.255.254"))
    assertTrue(isPrivateHost("192.168.1.1"))
    assertTrue(isPrivateHost("172.16.0.1"))
    assertTrue(isPrivateHost("172.31.255.254"))
    assertTrue(isPrivateHost("127.0.0.1"))
    assertTrue(isPrivateHost("169.254.10.20"))
    assertTrue(isPrivateHost("10.0.2.2")) // the emulator's host loopback
  }

  @Test
  fun `rejects public IPv4`() {
    assertFalse(isPrivateHost("8.8.8.8"))
    assertFalse(isPrivateHost("1.2.3.4"))
    assertFalse(isPrivateHost("93.184.216.34"))
  }

  @Test
  fun `gets the edges of 172-16-0-0 slash 12 right`() {
    // The range is 172.16 through 172.31 -- NOT all of 172.
    assertFalse(isPrivateHost("172.15.255.255"))
    assertTrue(isPrivateHost("172.16.0.0"))
    assertTrue(isPrivateHost("172.31.255.255"))
    assertFalse(isPrivateHost("172.32.0.0"))
    assertFalse(isPrivateHost("172.0.0.1"))
  }

  @Test
  fun `does not confuse a prefix with a private range`() {
    // A public address that merely starts with a private-looking octet.
    assertFalse(isPrivateHost("100.64.0.1"))
    assertFalse(isPrivateHost("11.0.0.1"))
    assertFalse(isPrivateHost("193.168.0.1"))
  }

  // ---- Names --------------------------------------------------------------

  @Test
  fun `accepts mDNS and single-label names`() {
    assertTrue(isPrivateHost("sensesp.local"))
    assertTrue(isPrivateHost("boat.local"))
    assertTrue(isPrivateHost("local"))
    assertTrue(isPrivateHost("boat")) // single label: no TLD, so no public meaning
    assertTrue(isPrivateHost("localhost"))
    assertTrue(isPrivateHost("BOAT.LOCAL")) // case-insensitive
    assertTrue(isPrivateHost("boat.local.")) // trailing dot is a legal FQDN
  }

  @Test
  fun `rejects public DNS names`() {
    assertFalse(isPrivateHost("example.com"))
    assertFalse(isPrivateHost("signalk.example.com"))
    // Ends with "local" but is not in the .local zone. The suffix tested is
    // ".local" with the dot, so this is not mistaken for mDNS.
    assertFalse(isPrivateHost("evil.notlocal"))
    assertFalse(isPrivateHost("evil.notlocal.com"))
    assertFalse(isPrivateHost("local.example.com")) // "local" as a mere label
  }

  @Test
  fun `a bare single-label name counts as private, by design`() {
    // No dots means no TLD, so the name cannot be delegated in public DNS and
    // can only be resolved by the local network -- which is why "localhost"
    // and a LAN hostname like "boat" work. Noted explicitly because it reads
    // as a hole otherwise: "notlocal" is private for this reason, not by an
    // accident of the .local check.
    assertTrue(isPrivateHost("notlocal"))
    assertTrue(isPrivateHost("anything-without-a-dot"))
  }

  // ---- IPv6 ---------------------------------------------------------------

  @Test
  fun `accepts private IPv6`() {
    assertTrue(isPrivateHost("::1")) // loopback
    assertTrue(isPrivateHost("fc00::1")) // unique local
    assertTrue(isPrivateHost("fd12:3456:789a::1"))
    assertTrue(isPrivateHost("fe80::1")) // link-local
    assertTrue(isPrivateHost("fe80::1%wlan0")) // with a zone id
    assertTrue(isPrivateHost("febf::1")) // top of fe80::/10
    assertTrue(isPrivateHost("[fd00::1]")) // bracketed, as it arrives from a URL
  }

  @Test
  fun `rejects public and unrecognised IPv6`() {
    assertFalse(isPrivateHost("2001:4860:4860::8888"))
    assertFalse(isPrivateHost("fec0::1")) // outside fe80::/10
    assertFalse(isPrivateHost("fe00::1"))
    // A group written short means leading zeros: "fd" is 0x00fd, not 0xfd00.
    assertFalse(isPrivateHost("fd::1"))
    // IPv4-mapped is not decoded; refusing is the safe direction.
    assertFalse(isPrivateHost("::ffff:10.0.0.1"))
    assertFalse(isPrivateHost("::ffff:8.8.8.8"))
  }

  // ---- Malformed input ----------------------------------------------------

  @Test
  fun `refuses malformed input rather than guessing`() {
    assertFalse(isPrivateHost(""))
    assertFalse(isPrivateHost("   "))
    assertFalse(isPrivateHost("192.168.0")) // not four octets
    assertFalse(isPrivateHost("192.168.0.100.5"))
    assertFalse(isPrivateHost("192.168.0.999")) // octet out of range
    assertFalse(isPrivateHost("zz80::1"))
  }

  // ---- The gate as ServerAddress exposes it -------------------------------

  @Test
  fun `TLS is allowed to any host, cleartext only to private ones`() {
    assertTrue(ServerAddress("boat.example.com", 3443, useTls = true).isCleartextSafe)
    assertFalse(ServerAddress("boat.example.com", 3000, useTls = false).isCleartextSafe)
    assertTrue(ServerAddress("sensesp.local", 3000).isCleartextSafe)
    assertTrue(ServerAddress("10.0.0.5", 3000).isCleartextSafe)
    assertFalse(ServerAddress("8.8.8.8", 3000).isCleartextSafe)
  }
}
