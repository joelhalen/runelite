/*
 * Copyright (c) 2026, joelhalen <https://github.com/joelhalen>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.framebench;

import java.util.Arrays;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;

/**
 * Benchmarks continuous frame capture over 10 second phases: an idle
 * baseline, recorder-style capture through requestNextFrameListener, and
 * capture through the frame listener API. Start with ::framebench.
 */
@PluginDescriptor(
	name = "Frame Bench",
	description = "Benchmark continuous frame capture paths (::framebench)",
	tags = {"benchmark", "capture", "fps"},
	developerPlugin = true
)
@Slf4j
public class FrameBenchPlugin extends Plugin
{
	private static final long PHASE_NANOS = 10_000_000_000L;
	private static final int MAX_SAMPLES = 100_000;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	private enum Phase
	{
		BASELINE,
		NEXT_FRAME,
		FRAME_LISTENER
	}

	private final Runnable everyFrameListener = this::onRenderFrame;
	private final DrawManager.FrameListener frameListener = this::onFrame;

	private boolean running;
	private Phase phase;
	private long phaseStart;
	private long lastFrameTime;
	private final long[] samples = new long[MAX_SAMPLES];
	private int sampleCount;
	private int framesDelivered;
	private int[] copyBuf;

	@Override
	protected void shutDown()
	{
		stop();
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted)
	{
		if (commandExecuted.getCommand().equals("framebench"))
		{
			if (running)
			{
				stop();
				message("frame bench: stopped");
			}
			else
			{
				start();
			}
		}
	}

	private void start()
	{
		running = true;
		phase = Phase.BASELINE;
		phaseStart = 0;
		sampleCount = 0;
		framesDelivered = 0;
		drawManager.registerEveryFrameListener(everyFrameListener);
		message("frame bench: baseline, 10s...");
	}

	private void stop()
	{
		running = false;
		drawManager.unregisterEveryFrameListener(everyFrameListener);
		drawManager.unregisterFrameListener(frameListener);
		copyBuf = null;
	}

	private void onRenderFrame()
	{
		if (!running)
		{
			return;
		}

		long now = System.nanoTime();
		if (phaseStart == 0)
		{
			phaseStart = now;
			lastFrameTime = now;
			return;
		}

		if (sampleCount < MAX_SAMPLES)
		{
			samples[sampleCount++] = now - lastFrameTime;
		}
		lastFrameTime = now;

		if (phase == Phase.NEXT_FRAME)
		{
			// recorder-style continuous capture through the legacy path; every
			// frame listeners run before next frame consumers, so this is
			// serviced later in this same frame
			drawManager.requestNextFrameListener(img -> framesDelivered++);
		}

		if (now - phaseStart >= PHASE_NANOS)
		{
			endPhase();
		}
	}

	private void onFrame(int[] pixels, int width, int height)
	{
		framesDelivered++;
		// minimal realistic consumer: keep a copy of the frame
		if (copyBuf == null || copyBuf.length != pixels.length)
		{
			copyBuf = new int[pixels.length];
		}
		System.arraycopy(pixels, 0, copyBuf, 0, pixels.length);
	}

	private void endPhase()
	{
		summarize();

		switch (phase)
		{
			case BASELINE:
				phase = Phase.NEXT_FRAME;
				message("frame bench: requestNextFrameListener capture, 10s...");
				break;
			case NEXT_FRAME:
				phase = Phase.FRAME_LISTENER;
				drawManager.registerFrameListener(frameListener);
				message("frame bench: frame listener capture, 10s...");
				break;
			case FRAME_LISTENER:
				stop();
				message("frame bench: done");
				return;
		}

		phaseStart = 0;
		sampleCount = 0;
		framesDelivered = 0;
	}

	private void summarize()
	{
		if (sampleCount == 0)
		{
			return;
		}

		long[] sorted = Arrays.copyOf(samples, sampleCount);
		Arrays.sort(sorted);
		long total = 0;
		for (int i = 0; i < sampleCount; ++i)
		{
			total += sorted[i];
		}

		double fps = sampleCount / (total / 1e9);
		double median = sorted[sampleCount / 2] / 1e6;
		double p95 = sorted[(int) (sampleCount * .95)] / 1e6;
		double p99 = sorted[(int) (sampleCount * .99)] / 1e6;
		double max = sorted[sampleCount - 1] / 1e6;

		String summary = String.format(
			"%s: %.1f fps, frame time median %.2f ms, p95 %.2f ms, p99 %.2f ms, max %.2f ms, frames delivered %d",
			phase, fps, median, p95, p99, max, framesDelivered);
		log.info(summary);
		message(summary);
	}

	private void message(String msg)
	{
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null));
	}
}
