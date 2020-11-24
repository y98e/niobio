package coin.run;

import coin.crypto.*;

public abstract class RunDaemonMiner extends Run {

	// Daemon + Miner. Git update and run it again.
	public static void main(final String[] args) throws Exception {
		// if its a valid key, continue. otherwise, exception (exit).
		Crypto.getPublicKeyFromString(args[0]);

		run("coin.run.DaemonMiner " + args[0]);
	}

}
