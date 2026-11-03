//! Self-signed TLS certificate generation for the wss:// mode.
//!
//! A fresh cert is generated in memory on every daemon startup. Clients must
//! either accept self-signed certs (the Android app does this when TLS mode is
//! selected) or the user can export the cert PEM printed at startup.

use std::sync::Arc;

use rcgen::generate_simple_self_signed;
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig,
};
use tokio_rustls::TlsAcceptor;

/// Build a `TlsAcceptor` backed by a fresh self-signed cert.
/// Also returns the cert in PEM form so the user can copy it if needed.
pub fn make_acceptor() -> (TlsAcceptor, String) {
    let certified = generate_simple_self_signed(vec!["voxwin".to_string()])
        .expect("self-signed cert generation failed");

    let pem = certified.cert.pem();
    let cert_der = CertificateDer::from(certified.cert.der().as_ref().to_vec());
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        certified.key_pair.serialize_der(),
    ));

    let config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], key_der)
        .expect("TLS ServerConfig failed");

    (TlsAcceptor::from(Arc::new(config)), pem)
}
