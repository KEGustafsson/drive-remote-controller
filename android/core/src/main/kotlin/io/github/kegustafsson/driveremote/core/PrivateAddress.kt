package io.github.kegustafsson.driveremote.core

/**
 * Is [host] an address on a private network?
 *
 * This exists because Android's network security config CANNOT express it.
 * `res/xml/network_security_config.xml` originally scoped cleartext to the
 * RFC1918 ranges with eighteen `<domain>192.168.</domain>`-style entries, on
 * the stated reasoning that "domain rules match by suffix, so the leading
 * octets cover the whole range". That reasoning is inverted. Android matches a
 * `<domain>` by SUFFIX -- hostname equals the domain, or ends with `"." +
 * domain` -- which is right for DNS names (`example.com` covering
 * `api.example.com`) and useless for IP addresses, whose network part is a
 * PREFIX. `192.168.0.100` does not end with `.192.168`, so not one of those
 * eighteen entries ever matched, and every connection to a boat server failed
 * with "CLEARTEXT communication to 192.168.0.100 not permitted by network
 * security policy". There is no CIDR or range syntax in that file to fix it
 * with.
 *
 * So the rule moves here, where it is real code with tests behind it rather
 * than a configuration that silently did nothing. The property being protected
 * is the one the XML comment described and never delivered: a mistyped or
 * hostile address must not silently carry a Signal K access token -- which
 * authorises commanding machinery -- in the clear to a public host.
 *
 * **Unrecognised forms return false**, which reads as "not private" and so
 * refuses cleartext. That is the safe direction, per AGENTS.md's rule on
 * screening untrusted boundaries: an address this function does not understand
 * is not thereby trusted. An exotic-but-legitimate IPv6 literal (an
 * IPv4-mapped `::ffff:10.0.0.1`, say) is rejected rather than parsed, and the
 * operator's remedy is to use `https://`, which is allowed unconditionally.
 */
fun isPrivateHost(host: String): Boolean {
  val trimmed = host.trim().removeSurrounding("[", "]").lowercase()
  // A trailing dot is a legal fully-qualified form ("boat.local.").
  val h = trimmed.removeSuffix(".")
  if (h.isEmpty()) return false

  return when {
    h.contains(':') -> isPrivateIpv6(h)
    isIpv4Literal(h) -> isPrivateIpv4(h)
    else -> isLocalName(h)
  }
}

private fun isIpv4Literal(h: String): Boolean {
  val parts = h.split('.')
  if (parts.size != 4) return false
  return parts.all { part ->
    part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() <= 255
  }
}

private fun isPrivateIpv4(h: String): Boolean {
  val o = h.split('.').map { it.toInt() }
  return when {
    o[0] == 10 -> true // 10.0.0.0/8
    o[0] == 127 -> true // 127.0.0.0/8, loopback
    o[0] == 192 && o[1] == 168 -> true // 192.168.0.0/16
    o[0] == 172 && o[1] in 16..31 -> true // 172.16.0.0/12
    o[0] == 169 && o[1] == 254 -> true // 169.254.0.0/16, link-local
    else -> false
  }
}

private fun isPrivateIpv6(h: String): Boolean {
  // A scope/zone id ("fe80::1%wlan0") is not part of the address.
  val addr = h.substringBefore('%')
  if (addr == "::1") return true // loopback

  val firstGroup = addr.substringBefore(':')
  // Empty means the literal opens with "::", and the only such address that is
  // private is ::1, handled above.
  if (firstGroup.isEmpty() || firstGroup.length > 4) return false
  if (!firstGroup.all { it in "0123456789abcdef" }) return false

  // Groups are written without leading zeros, so "fd" means 0x00fd -- padding
  // on the LEFT is what makes the first byte come out right.
  val group = firstGroup.padStart(4, '0')
  val firstByte = group.substring(0, 2).toInt(16)

  return when {
    firstByte == 0xfc || firstByte == 0xfd -> true // fc00::/7, unique local
    firstByte == 0xfe && group[2] in '8'..'b' -> true // fe80::/10, link-local
    else -> false
  }
}

private fun isLocalName(h: String): Boolean {
  // mDNS, which is what discovery returns.
  if (h == "local" || h.endsWith(".local")) return true
  // A single-label name has no public DNS meaning -- there is no TLD to
  // delegate it -- so it can only resolve on the local network.
  return !h.contains('.')
}
