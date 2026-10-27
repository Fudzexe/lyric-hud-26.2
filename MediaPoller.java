package dev.lyricshud;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Reads the currently playing track from the OS media controls - the same thing the volume flyout /
 * lock screen shows - so there is no Spotify login, API key or "connect URL" involved.
 *
 * Windows: Global System Media Transport Controls via a tiny PowerShell helper (works with Spotify,
 *          Apple Music, browsers, VLC, etc.).
 * Linux:   MPRIS via `playerctl` (install it from your package manager).
 */
public final class MediaPoller {
	private static final String SEP = "|||";

	// ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less files as ANSI.
	private static final String WINDOWS_SCRIPT = """
		param([string]$ArtPath)
		[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
		Add-Type -AssemblyName System.Runtime.WindowsRuntime
		$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
		function Await($op, $type) {
		  $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
		  $task.Wait(-1) | Out-Null
		  $task.Result
		}
		[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
		[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]
		$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
		[void][Windows.Storage.Streams.RandomAccessStreamReference, Windows.Storage.Streams, ContentType=WindowsRuntime]
		[void][Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]
		$runId = [guid]::NewGuid().ToString('N').Substring(0, 6)
		$md5 = [System.Security.Cryptography.MD5]::Create()
		$lastKey = ''
		$artToken = ''
		$artGen = 0
		$artDone = $true
		$artTries = 0
		$artErr = ''
		$prevHash = ''
		while ($true) {
		  $line = 'NONE'
		  try {
		    $s = $mgr.GetCurrentSession()
		    if ($s) {
		      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
		      $t = $s.GetTimelineProperties()
		      $status = $s.GetPlaybackInfo().PlaybackStatus.ToString()
		      $pos = $t.Position.TotalMilliseconds
		      $dur = ($t.EndTime - $t.StartTime).TotalMilliseconds
		      if ($status -eq 'Playing') {
		        $age = ([DateTimeOffset]::UtcNow - $t.LastUpdatedTime).TotalMilliseconds
		        if ($age -gt 0 -and $age -lt 600000) { $pos += $age }
		      }
		      if ($dur -gt 0 -and $pos -gt $dur) { $pos = $dur }
		      $title = "$($p.Title)".Replace('|', ' ')
		      $artist = "$($p.Artist)".Replace('|', ' ')
		      $key = "$title|$artist"
		      if ($key -ne $lastKey) { $lastKey = $key; $artDone = $false; $artTries = 0; $artErr = ''; $artToken = '' }
		      if (-not $artDone) {
		        if ($artTries -ge 60) {
		          $artDone = $true
		          $artToken = ''
		          if (-not $artErr) { $artErr = 'gave up waiting for a cover' }
		        } else {
		          $artTries++
		          $net = $null
		          try {
		            $ref = $p.Thumbnail
		            if (-not $ref) { $artErr = 'player has not provided a cover (yet)' }
		            else {
		              $st = Await ($ref.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
		              $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($st)
		              $ms = New-Object System.IO.MemoryStream
		              $net.CopyTo($ms)
		              $bytes = $ms.ToArray()
		              if ($bytes.Length -eq 0) { $artErr = 'cover stream was empty' }
		              else {
		                $hash = [System.BitConverter]::ToString($md5.ComputeHash($bytes))
		                if ($hash -eq $prevHash -and $artTries -lt 12) { $artErr = 'cover still matches the previous song, waiting' }
		                else {
		                  $tmp = $ArtPath + '.tmp'
		                  [System.IO.File]::WriteAllBytes($tmp, $bytes)
		                  Move-Item -LiteralPath $tmp -Destination $ArtPath -Force
		                  $prevHash = $hash
		                  $artGen++
		                  $artToken = "$runId-$artGen"
		                  $artDone = $true
		                  $artErr = ''
		                }
		              }
		            }
		          } catch {
		            $artErr = ("$($_.Exception.Message)").Replace('|', ' ').Replace([string][char]13, ' ').Replace([string][char]10, ' ')
		          } finally {
		            if ($net) { $net.Close() }
		          }
		        }
		      }
		      $line = "$status|||$([int64]($pos * 1000))|||$([int64]($dur * 1000))|||$title|||$artist|||$artToken|||$artErr"
		    }
		  } catch { $line = 'NONE' }
		  [Console]::Out.WriteLine($line)
		  [Console]::Out.Flush()
		  Start-Sleep -Milliseconds 250
		}
		""";

	private final MediaState state;
	private final LyricsConfig config;
	private volatile Process process;
	private String lastArtMsg = "";

	public MediaPoller(MediaState state, LyricsConfig config) {
		this.state = state;
		this.config = config;
	}

	public void start(Path configDir) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> cmd;
		if (os.contains("win")) {
			try {
				Path script = configDir.resolve("lyricshud-media.ps1");
				Files.writeString(script, WINDOWS_SCRIPT, StandardCharsets.US_ASCII);
				cmd = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
					"-ExecutionPolicy", "Bypass", "-File", script.toString(),
					"-ArtPath", configDir.resolve("lyricshud-art.img").toString());
			} catch (Exception e) {
				LyricsHudClient.LOGGER.error("Couldn't write media helper script", e);
				return;
			}
		} else if (os.contains("linux")) {
			String player = config.linuxPlayer == null ? "" : config.linuxPlayer.replaceAll("[^A-Za-z0-9._-]", "");
			String p = player.isEmpty() ? "" : " -p " + player;
			String fmt = "{{status}}" + SEP + "{{position}}" + SEP + "{{mpris:length}}" + SEP + "{{title}}" + SEP + "{{artist}}" + SEP + "{{mpris:artUrl}}";
			cmd = List.of("sh", "-c", "while true; do playerctl" + p + " metadata --format '" + fmt + "' 2>/dev/null || echo NONE; sleep 0.25; done");
		} else {
			LyricsHudClient.LOGGER.warn("Lyrics HUD: media detection isn't implemented for '{}' (Windows and Linux only).", os);
			return;
		}

		final List<String> command = cmd;
		Thread t = new Thread(() -> run(command), "lyricshud-media");
		t.setDaemon(true);
		t.start();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			Process p = process;
			if (p != null) p.destroyForcibly();
		}));
	}

	private void run(List<String> command) {
		while (true) {
			try {
				process = new ProcessBuilder(command)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
			} catch (Exception e) {
				LyricsHudClient.LOGGER.warn("Media helper failed to start: {}", e.toString());
				sleep(5000);
				continue;
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) handle(line);
			} catch (Exception ignored) {
			}
			state.clear();
			sleep(3000); // helper died - restart
		}
	}

	private void handle(String raw) {
		String line = raw.replace("﻿", "").trim();
		if (line.isEmpty() || line.equals("NONE")) {
			state.clear();
			return;
		}
		String[] f = line.split("\\|\\|\\|", -1);
		if (f.length < 5) return;
		try {
			boolean playing = f[0].equalsIgnoreCase("Playing");
			long posMs = Long.parseLong(f[1].trim()) / 1000L; // microseconds -> ms
			long durMs = f[2].isBlank() ? 0 : Long.parseLong(f[2].trim()) / 1000L;
			String title = f[3].trim();
			String artist = f[4].trim();
			if (title.isEmpty()) {
				state.clear();
				return;
			}
			if (f.length >= 6) state.setArtToken(f[5].trim());
			if (f.length >= 7) {
				String msg = f[6].trim();
				if (!msg.equals(lastArtMsg)) {
					lastArtMsg = msg;
					if (!msg.isEmpty()) LyricsHudClient.LOGGER.info("Album art helper: {}", msg);
				}
			}
			state.update(playing, posMs, durMs, title, artist);
		} catch (NumberFormatException ignored) {
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
		}
	}
}
