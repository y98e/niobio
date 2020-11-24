package coin.wallet;

import java.io.*;
import java.security.*;
import java.util.*;

import org.json.*;

import com.sun.net.httpserver.*;

import coin.crypto.*;
import coin.util.*;

public abstract class Wallet {

	public static void main(final String[] args) throws Exception {
		Thread.currentThread().setName("Wallet");
		Util.p("INFO: Starting Wallet");

		// http server = another thread (handleRequest is the "main" method)
		Util.startHttpServer(Util.conf.getInt("walletRPC"), x -> {
			try {
				handleRequest(x);
			} catch (final Exception e) {
				e.printStackTrace();
				System.exit(0);
			}
		});
	}

	public static String[] readKeys() {
		final File folder = new File(Util.conf.getString("folderKey"));
		final File[] pubkeys = folder.listFiles();
		final String[] keys = new String[pubkeys.length];

		final int qty = pubkeys.length;

		for (int i = 0; i < qty; i++) keys[i] = pubkeys[i].getName();

		return keys;
	}

	private static Obj createKey()
			throws NoSuchAlgorithmException, InvalidAlgorithmParameterException, IOException, NoSuchProviderException {
		ECKey keypair = new ECKey();
		String publicKeyString = Base64.getEncoder().encodeToString(keypair.getPubKey());
		do {
			keypair = new ECKey();
			publicKeyString = Base64.getEncoder().encodeToString(keypair.getPubKey());
		} while (publicKeyString.contains("/") || publicKeyString.contains("+"));

		final String privateKeyString = Base64.getEncoder()
				.encodeToString(Util.bigIntegerToBytes(keypair.getPrivKey(), 32));
		Util.writeToFile(Util.conf.getString("folderKey") + publicKeyString, privateKeyString);

		final Obj json = new Obj();
		json.put("pubkey", publicKeyString);
		return json;
	}

	private static Obj getKeys() {
		final Obj json = new Obj();
		json.put("keys", new Arr(readKeys()));
		return json;
	}

	private static Obj send(final String fromStr, final String toStr, final Long amount) throws Exception {

		final KeyPair key = Crypto.loadKey(fromStr);

		final Obj tx = RPC.getInputs(fromStr);

		final Long balance = tx.getLong("balance");
		tx.remove("balance");

		final Arr outputs = new Arr();
		final Obj out = new Obj("{\"pubkey\":\"" + toStr + "\", \"amount\":" + amount + "}");
		outputs.put(out);
		tx.put("outputs", outputs);

		Obj ret = new Obj();

		if (balance > amount) {
			final Obj change = new Obj("{\"pubkey\":\"" + fromStr + "\", \"amount\":" + (balance - amount) + "}");
			outputs.put(change);
		} else if (balance == amount) {
			// do nothing
		} else {
			ret.put("error", "amount bigger than balance");
			return ret;
		}

		tx.put("time", System.currentTimeMillis());

		final String txHash = Crypto.sha(tx);

		// signature
		tx.put("sig", Crypto.encodeHexString(Crypto.sign(key.getPrivate(), tx)));

		final Obj d = RPC.toDaemon(tx);

		if (d.has("status") && d.getBoolean("status")) {
			ret.put("txHash", txHash);
		} else {
			ret = d;
		}

		return ret;
	}

	// https://developer.bitcoin.org/reference/rpc/index.html#wallet-rpcs
	static void handleRequest(final HttpExchange exchange) throws Exception {
		Thread.currentThread().setName("RPC Wallet " + Util.random.nextInt(100));
		Obj json = Util.inputStreamToJSON(exchange.getRequestBody());
		Util.p("INFO: new request " + json);

		final String method = json.getString("method");

		switch (method) {

		case "createKey":
			json = createKey();
			break;

		case "send":
			final String from = json.getString("from");
			final String to = json.getString("to");
			final Long amount = json.getLong("amount");
			json = send(from, to, amount);
			break;

		case "getKeys":
			json = getKeys();
			break;

		}

		Util.response(exchange, json);
	}

}
