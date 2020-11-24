package coin.miner;

import java.io.*;

import org.json.*;

import com.sun.net.httpserver.*;

import coin.crypto.*;
import coin.util.*;

public class Miner {

	public static String pubkey;

	private static Thread mineThread;

	public static void main(final String[] args) throws Exception {

		Thread.currentThread().setName("Miner");
		Util.p("INFO: Starting Miner");

		// check if its a valid public key, otherwise exit exception
		Crypto.getPublicKeyFromString(args[0]);
		pubkey = args[0];

		// get block template to start mining
		Util.p("INFO: Miner to Daemon (asking for blockTemplate)");
		final Obj blockTemplate = RPC.getBlockTemplate();
		startMineThread(blockTemplate);

		if (args.length == 1) {
			Util.p("INFO: Starting RPC server");
			// http server = another thread (handleRequest is the "main" method)
			Util.startHttpServer(Util.conf.getInt("minerRPC"), x -> {
				try {
					handleRequest(x);
				} catch (final Exception e) {
					e.printStackTrace();
					System.exit(0);
				}
			});
		} else {
			Util.p("INFO: RPC server NOT started");
		}

	}

	private static void startMineThread(final Obj blockTemplate) throws IOException {
		if (mineThread != null) mineThread.interrupt();

		final Candidate c = new Candidate(pubkey, blockTemplate);
		mineThread = new Thread(() -> {
			try {
				Thread.currentThread().setName("Mining " + Util.random.nextInt(100));
				Util.p("INFO: start mining...");
				c.mine();
				Util.p("INFO: end mining thread");
			} catch (final IOException | InterruptedException e) {
				Util.p("WARN: end mining thread " + e.getMessage());
			}
		});
		mineThread.start();
	}

	static void handleRequest(final HttpExchange exchange) throws Exception {
		Thread.currentThread().setName("RPC Miner " + Util.random.nextInt(100));
		final Obj json = Util.inputStreamToJSON(exchange.getRequestBody());
		Util.p("INFO: new request " + json);

		if (json.has("method")) {
			final String method = json.getString("method");

			switch (method) {
			case "stop":
				Util.p("INFO: STOP mining!");
				mineThread.interrupt();
				mineThread = null;
				break;
			}
		} else {
			startMineThread(json);
		}
		Util.response(exchange, new Obj());
	}

}
