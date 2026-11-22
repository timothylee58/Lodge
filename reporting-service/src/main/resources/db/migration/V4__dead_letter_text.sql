-- Kafka keys and topic names are not bounded by our schema. The recorder must never fail on data size.
ALTER TABLE dead_letter ALTER COLUMN msg_key TYPE TEXT, ALTER COLUMN topic TYPE TEXT;
