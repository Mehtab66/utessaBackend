package com.crm.crm_backend.crm.application;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TLS trust for outbound calls to third-party APIs: the JDK's own CA list <em>plus</em> the
 * operating system's trusted roots (Windows certificate store / macOS keychain).
 *
 * <p>Antivirus "HTTPS scanning" (Avast, AVG, Kaspersky…) and corporate proxies re-sign HTTPS
 * traffic with their own root certificate, which they install in the OS store but not in Java's.
 * Without this, Java rejects those connections with "PKIX path building failed". Certificates are
 * still fully verified — a certificate must chain to a root that Java or the OS already trusts.
 */
final class SystemTrust {

	private static final Logger log = LoggerFactory.getLogger(SystemTrust.class);

	private SystemTrust() {
	}

	static SSLContext sslContext() {
		try {
			List<X509TrustManager> managers = new ArrayList<>();
			managers.add(managerFor(null)); // JDK cacerts
			String os = System.getProperty("os.name", "").toLowerCase();
			String storeType = os.contains("win") ? "Windows-ROOT" : os.contains("mac") ? "KeychainStore" : null;
			if (storeType != null) {
				try {
					KeyStore ks = KeyStore.getInstance(storeType);
					ks.load(null, null);
					managers.add(managerFor(ks));
				} catch (Exception ex) {
					log.warn("Operating-system certificate store {} not available: {}", storeType, ex.getMessage());
				}
			}
			SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(null, new TrustManager[] { new CompositeTrustManager(managers) }, null);
			return ctx;
		} catch (Exception ex) {
			log.warn("Falling back to the default TLS settings: {}", ex.getMessage());
			try {
				return SSLContext.getDefault();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static X509TrustManager managerFor(KeyStore ks) throws Exception {
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(ks);
		for (TrustManager tm : tmf.getTrustManagers()) {
			if (tm instanceof X509TrustManager x) {
				return x;
			}
		}
		throw new IllegalStateException("No X509 trust manager");
	}

	/** Trusts a chain when any one of the underlying trust managers trusts it. */
	private record CompositeTrustManager(List<X509TrustManager> managers) implements X509TrustManager {

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			check(chain, authType, true);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			check(chain, authType, false);
		}

		private void check(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
			CertificateException last = null;
			for (X509TrustManager m : managers) {
				try {
					if (client) {
						m.checkClientTrusted(chain, authType);
					} else {
						m.checkServerTrusted(chain, authType);
					}
					return;
				} catch (CertificateException ex) {
					last = ex;
				}
			}
			throw last != null ? last : new CertificateException("No trusted certificate path");
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return managers.stream().flatMap(m -> java.util.Arrays.stream(m.getAcceptedIssuers())).toArray(X509Certificate[]::new);
		}
	}
}
