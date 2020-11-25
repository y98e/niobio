package coin.util;

import org.json.*;

import coin.miner.*;
import coin.run.*;
import coin.wallet.*;

public abstract class Test {

	public static void main(final String[] args) throws Exception {

		DaemonWallet.main(args);

		// create keys
		// for (int i = 0; i < 1_000; i++) RPC.createKey();

		final String[] pubkeys = Wallet.readKeys();
		final int qty = pubkeys.length;

		Miner.main(new String[] { pubkeys[Util.random.nextInt(qty)] });

		new Thread(() -> {
			try {
				do {
					Thread.sleep(1000);
					Miner.pubkey = pubkeys[Util.random.nextInt(qty)];
				} while (true);
			} catch (final Exception e) {
				e.printStackTrace();
				System.exit(0);
			}
		}).start();

//		for (;;) {
//			final int idxFrom = Util.random.nextInt(qty);
//			final String from = pubkeys[idxFrom];
//
//			final int idxTo = Util.random.nextInt(qty);
//			final String to = pubkeys[idxTo];
//
//			final long balance = RPC.getBalance(from);
//
//			if (balance > 0) {
//				final long amount = balance - Util.random.nextInt((int) balance);
//				RPC.send(from, to, amount);
//			} else {
//				Thread.sleep(100);
//			}
//		}
	}

	public static void main1(final String[] args) throws Exception {
		DaemonOnly.main(args);
		final String json = "";
		final Obj o = new Obj(json);
		RPC.toDaemon(o);
	}

}
