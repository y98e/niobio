package coin.run;

import coin.wallet.*;

public abstract class WalletOnly {

	public static void main(final String[] args) {
		try {
			// start wallet
			final Thread wallet = new Thread(() -> {
				try {
					Wallet.main(args);
				} catch (final Exception e) {
					e.printStackTrace();
					System.exit(0);
				}
			});
			wallet.start();

			Thread.sleep(2500);
		} catch (final Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
}
