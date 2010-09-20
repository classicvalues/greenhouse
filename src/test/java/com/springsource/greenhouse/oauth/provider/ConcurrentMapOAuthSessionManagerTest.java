package com.springsource.greenhouse.oauth.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.security.encrypt.NoOpPasswordEncoder;
import org.springframework.security.encrypt.SearchableStringEncryptor;

import com.springsource.greenhouse.account.AppConnection;
import com.springsource.greenhouse.account.InvalidAccessTokenException;
import com.springsource.greenhouse.account.JdbcAccountRepository;
import com.springsource.greenhouse.account.StubFileStorage;
import com.springsource.greenhouse.database.GreenhouseTestDatabaseBuilder;
import com.springsource.greenhouse.oauth.provider.ConcurrentMapOAuthSessionManager;
import com.springsource.greenhouse.oauth.provider.InvalidRequestTokenException;
import com.springsource.greenhouse.oauth.provider.OAuthSession;

public class ConcurrentMapOAuthSessionManagerTest {

	private EmbeddedDatabase db;

	private ConcurrentMapOAuthSessionManager sessionManager;

	private JdbcAccountRepository accountRepository;

	@Before
	public void setUp() {
		db = new GreenhouseTestDatabaseBuilder().member().connectedApp().testData(getClass()).getDatabase();
		accountRepository = new JdbcAccountRepository(new JdbcTemplate(db), new SearchableStringEncryptor("secret", "5b8bd7612cdab5ed"),
				NoOpPasswordEncoder.getInstance(), new StubFileStorage(), "http://localhost:8080/members/{profileKey}");
		sessionManager = new ConcurrentMapOAuthSessionManager(accountRepository);
	}

	@After
	public void destroy() {
		if (db != null) {
			db.shutdown();
		}
	}

	@Test
	public void oAuth10SessionLifecycle() throws InvalidRequestTokenException, InvalidAccessTokenException {
		executeOAuthSessionLifecycle(2);
	}

	private void executeOAuthSessionLifecycle(int numberOfTimes) throws InvalidRequestTokenException,
			InvalidAccessTokenException {
		for (int i = 0; i < numberOfTimes; i++) {
			OAuthSession session = sessionManager.newOAuthSession("123456789",
					"x-com-springsource-greenhouse://oauth-response");
			assertEquals("123456789", session.getApiKey());
			assertEquals("x-com-springsource-greenhouse://oauth-response", session.getCallbackUrl());
			String requestToken = session.getRequestToken();
			String secret = session.getSecret();
			assertNotNull(requestToken);
			assertNotNull(secret);
			assertFalse(session.authorized());
			assertNull(session.getVerifier());

			session = sessionManager.getSession(session.getRequestToken());
			assertEquals("123456789", session.getApiKey());
			assertEquals("x-com-springsource-greenhouse://oauth-response", session.getCallbackUrl());
			assertEquals(requestToken, session.getRequestToken());
			assertEquals(secret, session.getSecret());
			assertFalse(session.authorized());
			assertNull(session.getVerifier());

			session = sessionManager.authorize(session.getRequestToken(), 1L, "verifier");
			assertEquals("123456789", session.getApiKey());
			assertEquals("x-com-springsource-greenhouse://oauth-response", session.getCallbackUrl());
			assertEquals(requestToken, session.getRequestToken());
			assertEquals(secret, session.getSecret());
			assertTrue(session.authorized());
			assertEquals("verifier", session.getVerifier());

			AppConnection connection = sessionManager.grantAccess(requestToken);
			assertEquals((Long) 1L, connection.getAccountId());
			assertEquals("123456789", connection.getApiKey());
			assertNotNull(connection.getAccessToken());
			assertFalse(requestToken.equals(connection.getAccessToken()));
			assertNotNull(connection.getSecret());
			assertFalse(secret.equals(connection.getSecret()));

			try {
				sessionManager.getSession(requestToken);
				fail("Should have thrown exception");
			} catch (InvalidRequestTokenException e) {

			}

			connection = accountRepository.findAppConnection(connection.getAccessToken());
			assertEquals((Long) 1L, connection.getAccountId());
			assertEquals("123456789", connection.getApiKey());
			assertNotNull(connection.getAccessToken());
			assertFalse(requestToken.equals(connection.getAccessToken()));
			assertNotNull(connection.getSecret());
			assertFalse(secret.equals(connection.getSecret()));
		}
	}
}