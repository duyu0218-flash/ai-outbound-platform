-- Old trials retain unknown metrics; they must not be presented as zero-cost native trials.
ALTER TABLE aivoicetrial ADD COLUMN metrics_json MEDIUMTEXT NOT NULL DEFAULT ('{}');
