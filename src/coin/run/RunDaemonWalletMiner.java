package coin.run;

import coin.crypto.*;

public abstract class RunDaemonWalletMiner extends Run {

	// Daemon + Wallet + Miner. Git update and run it again.
	public static void main(final String[] args) throws Exception {
		// if its a valid key, continue. otherwise, exception (exit).
		Crypto.getPublicKeyFromString(args[0]);

		run("coin.run.DaemonWalletMiner " + args[0]);
	}
}
