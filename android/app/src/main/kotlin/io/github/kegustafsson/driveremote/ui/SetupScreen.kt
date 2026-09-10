package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kegustafsson.driveremote.core.AccessState
import io.github.kegustafsson.driveremote.core.ServerAddress
import io.github.kegustafsson.driveremote.discovery.DiscoveredServer

/**
 * Choose a Signal K server: pick a discovered one, or type an address.
 *
 * Manual entry is shown alongside discovery rather than behind a "couldn't find
 * anything" fallback. Boat access points not uncommonly block multicast, and an
 * operator who knows their server's address should not have to wait out a scan
 * that is never going to succeed.
 */
@Composable
fun ServerScreen(
  discovered: List<DiscoveredServer>,
  onChoose: (ServerAddress) -> Unit,
  modifier: Modifier = Modifier,
  error: String? = null,
  notice: String? = null,
  lastServer: ServerAddress? = null,
) {
  var typed by remember { mutableStateOf("") }
  val parsed = ServerAddress.parse(typed)
  val typedIsInvalid = typed.isNotBlank() && parsed == null
  // Syntactically fine but off the private network: say so before the operator
  // taps Connect, rather than letting the ViewModel reject it afterwards. The
  // ViewModel still enforces it -- this is only the earlier, kinder telling.
  val typedIsExposed = parsed != null && !parsed.isCleartextSafe

  SetupSurface(modifier) {
    val helm = LocalHelmScale.current
    Text(
      "Signal K server",
      fontSize = helm.text(22.sp),
      fontWeight = FontWeight.Bold,
      color = DriveColors.ink,
    )
    Text(
      "Pick the boat's server, or type its address.",
      fontSize = helm.text(13.sp),
      color = DriveColors.inkMuted,
      modifier = Modifier.padding(top = helm.size(4.dp), bottom = helm.size(12.dp)),
    )

    // Why the app came back here on its own. Shown before anything else,
    // because otherwise returning to this screen mid-session reads as the app
    // having lost its settings rather than the server having withdrawn access.
    if (notice != null) {
      Text(
        notice,
        fontSize = helm.text(13.sp),
        color = DriveColors.warn,
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DriveColors.surfaceRaised)
            .padding(helm.size(12.dp)),
      )
    }

    // The server just used, offered as one tap. Re-authorising after a
    // withdrawn token is then two taps rather than retyping an address on a
    // moving boat -- and it is the same code path as any other choice, so the
    // token check and the cleartext gate both still apply.
    if (lastServer != null) {
      Column(
        Modifier.fillMaxWidth()
          .padding(top = helm.size(10.dp))
          .clip(RoundedCornerShape(10.dp))
          .background(DriveColors.surfaceRaised)
          .clickable { onChoose(lastServer) }
          .padding(helm.size(12.dp))
      ) {
        Text("Reconnect", fontSize = helm.text(13.sp), color = DriveColors.inkMuted)
        Text(lastServer.toString(), fontSize = helm.text(16.sp), color = DriveColors.ink)
      }
    }

    OutlinedTextField(
      value = typed,
      onValueChange = { typed = it },
      label = { Text("Address, e.g. 192.168.0.100:3000") },
      singleLine = true,
      isError = typedIsInvalid || typedIsExposed,
      supportingText = {
        when {
          typedIsInvalid -> Text("Not an address this app can use", color = DriveColors.bad)
          typedIsExposed ->
            Text(
              "Not a private-network address. Use https:// to reach a server outside the " +
                "boat network — over plain http the access token would go out in the clear.",
              color = DriveColors.bad,
            )
        }
      },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
      modifier = Modifier.fillMaxWidth(),
    )

    Button(
      onClick = { parsed?.let(onChoose) },
      enabled = parsed != null && !typedIsExposed,
      modifier = Modifier.fillMaxWidth().padding(top = helm.size(8.dp)),
    ) {
      Text("Connect")
    }

    // A refusal that came from the ViewModel -- which is the path a DISCOVERED
    // server takes, since it never passes through the text field above.
    if (error != null) {
      Text(
        error,
        fontSize = helm.text(13.sp),
        color = DriveColors.bad,
        modifier = Modifier.padding(top = helm.size(8.dp)),
      )
    }

    Text(
      if (discovered.isEmpty()) "Searching the network…" else "Found on the network",
      fontSize = helm.text(13.sp),
      color = DriveColors.inkMuted,
      modifier = Modifier.padding(top = helm.size(20.dp), bottom = helm.size(6.dp)),
    )

    LazyColumn(Modifier.fillMaxWidth()) {
      items(discovered) { server ->
        Column(
          Modifier.fillMaxWidth()
            .padding(vertical = helm.size(4.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(DriveColors.surfaceRaised)
            .clickable { onChoose(server.address) }
            .padding(helm.size(12.dp))
        ) {
          Text(server.name, fontSize = helm.text(16.sp), color = DriveColors.ink)
          Text(server.address.toString(), fontSize = helm.text(13.sp), color = DriveColors.inkMuted)
          server.software?.let {
            Text(it, fontSize = helm.text(11.sp), color = DriveColors.inkMuted)
          }
        }
      }
    }
  }
}

/**
 * The access-request screen: ask for a token, then wait for an admin to approve
 * it in the Signal K admin UI.
 *
 * The wait is explained rather than shown as a bare spinner, because the next
 * step happens on a different device and nothing on this screen will change
 * until someone walks over and clicks approve.
 */
@Composable
fun AccessScreen(
  server: ServerAddress,
  state: AccessState,
  onRequest: () -> Unit,
  onChangeServer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SetupSurface(modifier) {
    val helm = LocalHelmScale.current
    Text(
      "Authorise this device",
      fontSize = helm.text(22.sp),
      fontWeight = FontWeight.Bold,
      color = DriveColors.ink,
    )
    Text(
      "Commanding the drives is a write to Signal K, so this device needs its own access token — " +
        "the same way the TX, RX and thruster units get theirs.",
      fontSize = helm.text(13.sp),
      color = DriveColors.inkMuted,
      modifier = Modifier.padding(top = helm.size(6.dp)),
    )

    Text(
      "Server: $server",
      fontSize = helm.text(13.sp),
      color = DriveColors.ink,
      modifier = Modifier.padding(top = helm.size(12.dp)),
    )

    when (state) {
      is AccessState.None ->
        Button(
          onClick = onRequest,
          modifier = Modifier.fillMaxWidth().padding(top = helm.size(16.dp)),
        ) {
          Text("Request access")
        }

      is AccessState.Pending ->
        Text(
          "Waiting for approval.\n\nOn the Signal K server, open Security → Access Requests and " +
            "approve this device with read/write permission. Admin is not needed.",
          fontSize = helm.text(14.sp),
          color = DriveColors.warn,
          modifier = Modifier.padding(top = helm.size(16.dp)),
        )

      is AccessState.Denied ->
        Column(Modifier.padding(top = helm.size(16.dp))) {
          Text("The request was denied.", fontSize = helm.text(14.sp), color = DriveColors.bad)
          Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth().padding(top = helm.size(8.dp)),
          ) {
            Text("Ask again")
          }
        }

      is AccessState.Failed ->
        Column(Modifier.padding(top = helm.size(16.dp))) {
          Text(state.reason, fontSize = helm.text(14.sp), color = DriveColors.bad)
          Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth().padding(top = helm.size(8.dp)),
          ) {
            Text("Try again")
          }
        }

      is AccessState.Approved ->
        Text(
          "Approved.",
          fontSize = helm.text(14.sp),
          color = DriveColors.good,
          modifier = Modifier.padding(top = helm.size(16.dp)),
        )
    }

    Row(
      Modifier.fillMaxWidth().padding(top = helm.size(24.dp)),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "Use a different server",
        fontSize = helm.text(13.sp),
        color = DriveColors.inkMuted,
        modifier = Modifier.clickable(onClick = onChangeServer),
      )
    }
  }
}

/**
 * The frame both setup screens sit in.
 *
 * Two jobs, and the second is the one that matters on a tablet. It provides the
 * [HelmScale] — these screens are composed outside [ControlScreen], so without
 * this they would render at reference-phone sizes on a 10" tablet while the
 * control screen beside them scaled. And it caps the content width: a line of
 * explanatory text 1280 dp wide is not using the screen well, it is just hard to
 * read, and a Connect button that spans the whole of a landscape tablet looks
 * like a mistake. Capped and centred, which is what the width is for here.
 *
 * The control screen deliberately does NOT do this: there, width is what the
 * three-column arrangement spends on putting the drives under two thumbs.
 */
@Composable
private fun SetupSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    val helm = remember(maxWidth, maxHeight) { helmScaleFor(maxWidth, maxHeight) }
    CompositionLocalProvider(LocalHelmScale provides helm) {
      Column(
        Modifier.widthIn(max = SetupContentMaxWidth)
          .fillMaxSize()
          .align(Alignment.TopCenter)
          .padding(helm.size(16.dp)),
        content = content,
      )
    }
  }
}

/** About the width of a page of text, and roughly a phone's worth of controls. */
private val SetupContentMaxWidth = 600.dp
