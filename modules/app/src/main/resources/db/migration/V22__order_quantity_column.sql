-- Add quantity column to orders table (default 1 for existing rows)
ALTER TABLE orders ADD COLUMN quantity INT NOT NULL DEFAULT 1;
