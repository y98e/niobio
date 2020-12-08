package coin.util;

import java.io.*;

import org.json.*;

public abstract class RPC {

	public static Obj createKey() throws IOException {
		return toWallet(new Obj("{\"method\":\"createKey\"}"));
	}

	public static long getBalance(final String from) throws JSONException, IOException {
		final Obj o = toDaemon(new Obj("{\"method\":\"getBalance\", \"pubkey\":\"" + from + "\"}"));
		return o.getLong("balance");
	}

	public static Obj getBlockTemplate() throws IOException {
		return toDaemon(new Obj("{\"method\":\"getBlockTemplate\"}"));
	}

	public static Obj getInputs(final String fromStr) throws IOException {
		return toDaemon(new Obj("{\"method\":\"getInputs\", \"pubkey\":\"" + fromStr + "\"}"));
	}

	public static void send(final String from, final String to, final long amount) throws JSONException, IOException {
		toWallet(new Obj(
				"{\"method\":\"send\", \"from\":\"" + from + "\", \"to\":\"" + to + "\", \"amount\":" + amount + "}"));
	}

	public static Obj toDaemon(final Obj o) {
		return Util.postRPC("http://localhost:" + Util.conf.getInt("daemonRPC"), o);
	}

	public static Obj toExplorer(final Obj o) {
		return Util.postRPC("http://localhost:" + Util.conf.getInt("explorerRPC"), o);
	}

	public static Obj toMiner(final Obj o) {
		return Util.postRPC("http://localhost:" + Util.conf.getInt("minerRPC"), o);
	}

	public static Obj toWallet(final Obj o) {
		return Util.postRPC("http://localhost:" + Util.conf.getInt("walletRPC"), o);
	}

}
