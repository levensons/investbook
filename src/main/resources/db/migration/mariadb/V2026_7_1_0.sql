/* Security balances from broker reports used for current portfolio valuation. */
CREATE TABLE IF NOT EXISTS `security_position` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `account` VARCHAR(32) NOT NULL,
    `security` INT NOT NULL,
    `timestamp` TIMESTAMP(6) NOT NULL,
    `count` DECIMAL(38, 10) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `security_position_account_security_timestamp_uniq_ix` (`account`, `security`, `timestamp`),
    KEY `security_position_account_timestamp_ix` (`account`, `timestamp`),
    CONSTRAINT `security_position_account_fkey`
        FOREIGN KEY (`account`) REFERENCES `account` (`id`) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT `security_position_security_fkey`
        FOREIGN KEY (`security`) REFERENCES `security` (`id`) ON UPDATE CASCADE ON DELETE CASCADE
);
