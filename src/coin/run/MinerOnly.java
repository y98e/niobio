package coin.run;

import coin.miner.*;

public abstract class MinerOnly {

	public static void main(final String[] args) {
		try {
			// start miner
			final Thread miner = new Thread(() -> {
				try {
					Miner.main(args);
				} catch (final Exception e) {
					e.printStackTrace();
					System.exit(0);
				}
			});
			miner.start();

			Thread.sleep(2500);
		} catch (final Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
}
