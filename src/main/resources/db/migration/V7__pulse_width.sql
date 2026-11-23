-- The third group of the timing summary: minimum pulse width and period checks on the clocks.
-- Reports read before this was kept carry zeros, which is what a design with no pulse width
-- failures reports anyway.

ALTER TABLE build_reports ADD COLUMN wpws_ps INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE build_reports ADD COLUMN tpws_ps INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE build_reports ADD COLUMN pulse_failing INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE build_reports ADD COLUMN pulse_total INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE build_reports ADD CONSTRAINT ck_reports_pulse_failing CHECK (pulse_failing >= 0);
