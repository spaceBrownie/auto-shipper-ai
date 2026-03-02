-- Baseline migration: schema skeleton
-- Bounded context tables will be added in subsequent FRs

-- Ensure UUID extension is available
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
