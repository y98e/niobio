package coin.daemon;

import org.json.*;

import com.sun.net.httpserver.*;

import coin.util.*;

public abstract class Daemon {

	private static Node node;

	private static Blockchain blockchain;

	// load blockchain, start p2p network and http server
	public static void main(final String[] args) throws Exception {

		Thread.currentThread().setName("Daemon");
		Util.p("INFO: Starting Daemon");

		// start explorer
		final Thread explorer = new Thread(() -> {
			try {
				Explorer.main(null);
			} catch (final Exception e) {
				Util.p(e.getMessage());
			}
		});
		explorer.start();

		// load blockchain
		blockchain = new Blockchain();

		// connect to the p2p network
		node = new Node(blockchain);

		// http server = another thread (handleRequest is the "main" method)
		Util.startHttpServer(Util.conf.getInt("daemonRPC"), x -> {
			try {
				handleRequest(x);
			} catch (final Exception e) {
				e.printStackTrace();
				System.exit(0);
			}
		});

		// run forever
		while (true) {
			boolean sleep = false;
			synchronized (blockchain) {
				final boolean somethingToDo = node.p2pHandler();
				if (!somethingToDo) sleep = node.shouldIDoSomethingNow();
			}
			if (sleep) Thread.sleep(1000);
		}

	}

	private static Obj circulatingSupply() {
		final Obj utxo = blockchain.bestChain.getObj("UTXO");
		Long balance = 0L;
		for (final String txHash : Obj.getNames(utxo)) {
			final Obj tx = utxo.getObj(txHash);
			final Arr outputs = tx.getArr("outputs");

			for (int i = 0; i < outputs.length(); i++) {
				final Obj out = outputs.getObj(i);
				if (!out.has("pubkey")) continue;
				balance += out.getLong("amount");
			}
		}

		final Obj r = new Obj();
		r.put("balance", balance);

		return r;
	}

	// get balance, check if this user has txs in mempool and get last transactions data
	private static Obj getBalance(final String pubkey) {
		final Obj utxo = blockchain.bestChain.getObj("UTXO");
		Long balance = 0L;
		for (final String txHash : Obj.getNames(utxo)) {
			final Obj tx = utxo.getObj(txHash);
			final Arr outputs = tx.getArr("outputs");

			for (int i = 0; i < outputs.length(); i++) {
				final Obj out = outputs.getObj(i);
				if (!out.has("pubkey")) continue;
				if (out.getString("pubkey").equals(pubkey)) {
					balance += out.getLong("amount");
				}
			}
		}

		final Obj r = new Obj();
		r.put("balance", balance);

		for (final String s : node.mempoolTxs) {
			if (s.contains(pubkey)) {
				r.put("mempool", "Tx received. Waiting to be mined.");
				break;
			}
		}

		return r;
	}

	private static Obj getInputs(final String pubkey) {
		final Obj utxo = blockchain.bestChain.getObj("UTXO");
		final Obj newtx = new Obj();
		final Arr inputs = new Arr();

		long balance = 0;
		for (final String txHash : Obj.getNames(utxo)) {
			final Obj tx = utxo.getObj(txHash);
			final Arr outputs = tx.getArr("outputs");

			for (int i = 0; i < outputs.length(); i++) {
				final Obj out = outputs.getObj(i);
				if (!out.has("pubkey")) continue;
				if (out.getString("pubkey").equals(pubkey)) {
					final Obj input = new Obj();
					input.put("txHash", txHash);
					input.put("outIdx", i);
					inputs.put(input);
					balance += out.getLong("amount");
				}
			}
		}

		newtx.put("inputs", inputs);
		newtx.put("balance", balance);

		return newtx;
	}

	static Obj getBlockTemplate() throws Exception {
		final Obj candidate = new Obj(); // Block
		candidate.put("time", System.currentTimeMillis());
		candidate.put("lastBlockHash", blockchain.bestChain.getString("blockHash"));
		candidate.put("txs", node.getFromMemPool());
		candidate.put("target", blockchain.bestChain.getString("target"));
		candidate.put("reward", DUtil.getNextReward(blockchain.bestChain));
		candidate.put("height", blockchain.bestChain.getLong("height"));
		return candidate;
	}

	static void handleRequest(final HttpExchange exchange) throws Exception {

		Thread.currentThread().setName("RPC Daemon " + Util.random.nextInt(100));

		Obj json = Util.inputStreamToJSON(exchange.getRequestBody());
		Obj blockOrTX = null;

		Util.p("INFO: new request " + json);

		// this request should be sent to the network? (new valid block or transaction = true)
		boolean sendToNetwork = false;

		synchronized (blockchain) {

			if (json.has("method")) {

				final String method = json.getString("method");

				switch (method) {
				case "getInputs":
					json = getInputs(json.getString("pubkey"));
					break;

				case "getBalance":
					json = getBalance(json.getString("pubkey"));
					break;

				case "getBlockTemplate":
					json = getBlockTemplate();
					break;

				case "getBestChain":
					json = blockchain.bestChain;
					break;

				case "getMempool":
					final Arr mempool = new Arr(node.mempoolTxs);
					json = new Obj();
					json.put("mempool", mempool);
					break;

				case "circulatingSupply":
					json = circulatingSupply();
					break;

				case "getBlock":
					json = RPC.toExplorer(json);
					break;

				case "getTransaction":
					json = RPC.toExplorer(json);
					break;
				}

			} else if (json.has("lastBlockHash")) { // block
				sendToNetwork = blockchain.addBlock(json, null);
				blockOrTX = json;
				json = new Obj("{\"status\":\"" + sendToNetwork + "\"}");

			} else if (json.has("sig")) { // tx
				sendToNetwork = node.addTxToMemPool(json);
				blockOrTX = json;
				json = new Obj("{\"status\":\"" + sendToNetwork + "\"}");
			}

			if (sendToNetwork) node.sendAll(blockOrTX);

		}

		Util.response(exchange, json);
	}
}
