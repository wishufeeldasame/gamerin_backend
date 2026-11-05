CREATE TABLE membership_grants (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    payment_id UUID NOT NULL UNIQUE REFERENCES payments(id),
    granted_from TIMESTAMP WITH TIME ZONE NOT NULL,
    granted_until TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('GRANTED', 'REVOKED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_membership_grants_period CHECK (granted_until > granted_from)
);

CREATE INDEX idx_membership_grants_user_created_at ON membership_grants (user_id, created_at DESC);
