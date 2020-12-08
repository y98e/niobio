package coin.run;

import coin.daemon.*;

public abstract class DaemonOnly {

	public static void main(final String[] args) {
		try {
			// start daemon
			final Thread daemon = new Thread(() -> {
				try {
					Daemon.main(null);
				} catch (final Exception e) {
					e.printStackTrace();
					System.exit(0);
				}
			});
			daemon.start();

			Thread.sleep(30_000);
		} catch (final Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
}
