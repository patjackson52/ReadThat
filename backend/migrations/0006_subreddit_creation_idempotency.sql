ALTER TABLE subreddits ADD COLUMN client_mutation_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_subreddits_creator_mutation
  ON subreddits(created_by, client_mutation_id)
  WHERE client_mutation_id IS NOT NULL;
