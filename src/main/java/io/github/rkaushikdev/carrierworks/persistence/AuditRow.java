package io.github.rkaushikdev.carrierworks.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;

/**
 * One thing somebody did, kept whether or not it worked.
 *
 * <p>The trail is append only and nothing in the bench deletes from it. Its whole value is answering
 * who took a board at two in the morning, and a table anybody can tidy up cannot answer that.
 */
@Entity
@Table(
        name = "audit",
        indexes = {
            @Index(name = "ix_audit_at", columnList = "at"),
            @Index(name = "ix_audit_subject", columnList = "subject"),
            @Index(name = "ix_audit_actor", columnList = "actor")
        })
@Check(name = "ck_audit_action", constraints = "length(action) > 0")
public class AuditRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "actor", nullable = false, length = 64)
    private String actor;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "subject", nullable = false, length = 64)
    private String subject = "";

    @Column(name = "detail", nullable = false, length = 240)
    private String detail = "";

    @Column(name = "key_id", nullable = false, length = 32)
    private String keyId = "";

    /** An empty row, filled in through its setters. */
    public AuditRow() {}

    public Integer getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant value) {
        at = value;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String value) {
        actor = value;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String value) {
        action = value;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String value) {
        subject = value;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String value) {
        detail = value;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String value) {
        keyId = value;
    }
}
