
CREATE TABLE pending_registrations (
	id INTEGER NOT NULL, 
	email VARCHAR(255) NOT NULL, 
	name VARCHAR(255) NOT NULL, 
	cpf VARCHAR(11) NOT NULL, 
	password_hash VARCHAR(255) NOT NULL, 
	birth_date DATE NOT NULL, 
	sex VARCHAR(32) NOT NULL, 
	code_hash VARCHAR(64) NOT NULL, 
	attempts INTEGER NOT NULL, 
	expires_at DATETIME NOT NULL, 
	created_at DATETIME NOT NULL, 
	PRIMARY KEY (id)
)

;
CREATE UNIQUE INDEX ix_pending_registrations_email ON pending_registrations (email);
CREATE INDEX ix_pending_registrations_cpf ON pending_registrations (cpf);

CREATE TABLE users (
	id INTEGER NOT NULL, 
	email VARCHAR(255) NOT NULL, 
	name VARCHAR(255), 
	cpf VARCHAR(11), 
	password_hash VARCHAR(255), 
	google_id VARCHAR(255), 
	birth_date DATE, 
	sex VARCHAR(32), 
	PRIMARY KEY (id), 
	UNIQUE (google_id)
)

;
CREATE UNIQUE INDEX ix_users_email ON users (email);
CREATE INDEX ix_users_cpf ON users (cpf);

CREATE TABLE bank_accounts (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	institution_name VARCHAR(255) NOT NULL, 
	institution_id VARCHAR(255), 
	account_name VARCHAR(255), 
	masked_account VARCHAR(100), 
	external_account_id VARCHAR(255), 
	current_balance NUMERIC(14, 2), 
	balance_updated_at DATETIME, 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE, 
	UNIQUE (external_account_id)
)

;
CREATE INDEX ix_bank_accounts_user_id ON bank_accounts (user_id);

CREATE TABLE categories (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	name VARCHAR(100) NOT NULL, 
	icon VARCHAR(20), 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_categories_user_id ON categories (user_id);

CREATE TABLE credit_cards (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	bank_name VARCHAR(255) NOT NULL, 
	brand VARCHAR(60) NOT NULL, 
	last_four VARCHAR(4) NOT NULL, 
	nickname VARCHAR(120), 
	active BOOLEAN NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_credit_cards_user_id ON credit_cards (user_id);

CREATE TABLE financial_goals (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	name VARCHAR(160) NOT NULL, 
	target_amount NUMERIC(14, 2) NOT NULL, 
	current_amount NUMERIC(14, 2) NOT NULL, 
	target_date DATE, 
	created_at DATETIME NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_financial_goals_user_id ON financial_goals (user_id);

CREATE TABLE financial_reset_markers (
	user_id INTEGER NOT NULL, 
	reset_at DATETIME NOT NULL, 
	PRIMARY KEY (user_id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
)

;

CREATE TABLE ignored_accounts (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	external_account_id VARCHAR(255) NOT NULL, 
	ignored_at DATETIME NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_ignored_user_external_account UNIQUE (user_id, external_account_id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_ignored_accounts_external_account_id ON ignored_accounts (external_account_id);
CREATE INDEX ix_ignored_accounts_user_id ON ignored_accounts (user_id);

CREATE TABLE open_finance_connections (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	provider VARCHAR(100) NOT NULL, 
	institution_id VARCHAR(255), 
	institution_name VARCHAR(255), 
	external_connection_id VARCHAR(255), 
	consent_id VARCHAR(255), 
	status VARCHAR(50) NOT NULL, 
	authorization_url TEXT, 
	access_token_encrypted TEXT, 
	refresh_token_encrypted TEXT, 
	expires_at DATETIME, 
	last_sync_at DATETIME, 
	next_sync_at DATETIME, 
	sync_interval_minutes INTEGER NOT NULL, 
	mock_sequence INTEGER NOT NULL, 
	last_error TEXT, 
	created_at DATETIME NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE, 
	UNIQUE (external_connection_id), 
	UNIQUE (consent_id)
)

;
CREATE INDEX ix_open_finance_connections_user_id ON open_finance_connections (user_id);
CREATE INDEX ix_open_finance_connections_next_sync_at ON open_finance_connections (next_sync_at);
CREATE INDEX ix_open_finance_connections_status ON open_finance_connections (status);

CREATE TABLE refresh_tokens (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	token_hash VARCHAR(128) NOT NULL, 
	expires_at DATETIME NOT NULL, 
	revoked BOOLEAN NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
)

;
CREATE UNIQUE INDEX ix_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);

CREATE TABLE user_data_deletion_codes (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	code_hash VARCHAR(64) NOT NULL, 
	attempts INTEGER NOT NULL, 
	expires_at DATETIME NOT NULL, 
	created_at DATETIME NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_user_data_deletion_codes_user_id ON user_data_deletion_codes (user_id);

CREATE TABLE user_sync_states (
	user_id INTEGER NOT NULL, 
	last_full_sync_at DATETIME, 
	updated_at DATETIME NOT NULL, 
	PRIMARY KEY (user_id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
)

;

CREATE TABLE account_deletion_codes (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	account_id INTEGER NOT NULL, 
	code_hash VARCHAR(64) NOT NULL, 
	attempts INTEGER NOT NULL, 
	expires_at DATETIME NOT NULL, 
	created_at DATETIME NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE, 
	FOREIGN KEY(account_id) REFERENCES bank_accounts (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_account_deletion_codes_account_id ON account_deletion_codes (account_id);
CREATE INDEX ix_account_deletion_codes_user_id ON account_deletion_codes (user_id);

CREATE TABLE category_budgets (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	category_id INTEGER NOT NULL, 
	amount NUMERIC(14, 2) NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_category_budget_user_category UNIQUE (user_id, category_id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE, 
	FOREIGN KEY(category_id) REFERENCES categories (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_category_budgets_user_id ON category_budgets (user_id);
CREATE INDEX ix_category_budgets_category_id ON category_budgets (category_id);

CREATE TABLE goal_contributions (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	goal_id INTEGER NOT NULL, 
	amount NUMERIC(14, 2) NOT NULL, 
	client_key VARCHAR(120) NOT NULL, 
	created_at DATETIME NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_goal_contribution_user_client_key UNIQUE (user_id, client_key), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE, 
	FOREIGN KEY(goal_id) REFERENCES financial_goals (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_goal_contributions_client_key ON goal_contributions (client_key);
CREATE INDEX ix_goal_contributions_goal_id ON goal_contributions (goal_id);
CREATE INDEX ix_goal_contributions_user_id ON goal_contributions (user_id);

CREATE TABLE ignored_transactions (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	account_id INTEGER NOT NULL, 
	external_transaction_id VARCHAR(255) NOT NULL, 
	ignored_at DATETIME NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_ignored_account_external UNIQUE (account_id, external_transaction_id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE, 
	FOREIGN KEY(account_id) REFERENCES bank_accounts (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_ignored_transactions_account_id ON ignored_transactions (account_id);
CREATE INDEX ix_ignored_transactions_user_id ON ignored_transactions (user_id);
CREATE INDEX ix_ignored_transactions_external_transaction_id ON ignored_transactions (external_transaction_id);

CREATE TABLE open_finance_sync_logs (
	id INTEGER NOT NULL, 
	connection_id INTEGER NOT NULL, 
	status VARCHAR(50) NOT NULL, 
	imported_transactions INTEGER NOT NULL, 
	message TEXT, 
	started_at DATETIME NOT NULL, 
	finished_at DATETIME, 
	PRIMARY KEY (id), 
	FOREIGN KEY(connection_id) REFERENCES open_finance_connections (id) ON DELETE CASCADE
)

;
CREATE INDEX ix_open_finance_sync_logs_connection_id ON open_finance_sync_logs (connection_id);
CREATE INDEX ix_open_finance_sync_logs_status ON open_finance_sync_logs (status);

CREATE TABLE transactions (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	account_id INTEGER, 
	category_id INTEGER, 
	card_id INTEGER, 
	installment_group VARCHAR(120), 
	installment_number INTEGER, 
	installment_total INTEGER, 
	purchase_date DATETIME, 
	external_transaction_id VARCHAR(255), 
	source VARCHAR(50) NOT NULL, 
	date DATETIME NOT NULL, 
	description TEXT NOT NULL, 
	amount NUMERIC(14, 2) NOT NULL, 
	transaction_type VARCHAR(50) NOT NULL, 
	status VARCHAR(50) NOT NULL, 
	synced_at DATETIME, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_transaction_account_external UNIQUE (account_id, external_transaction_id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE, 
	FOREIGN KEY(account_id) REFERENCES bank_accounts (id) ON DELETE CASCADE, 
	FOREIGN KEY(category_id) REFERENCES categories (id) ON DELETE SET NULL, 
	FOREIGN KEY(card_id) REFERENCES credit_cards (id) ON DELETE SET NULL
)

;
CREATE INDEX ix_transactions_account_id ON transactions (account_id);
CREATE INDEX ix_transactions_user_id ON transactions (user_id);
CREATE INDEX ix_transactions_card_id ON transactions (card_id);
CREATE INDEX ix_transactions_external_transaction_id ON transactions (external_transaction_id);
CREATE INDEX ix_transactions_installment_group ON transactions (installment_group);
CREATE INDEX ix_transactions_source ON transactions (source);