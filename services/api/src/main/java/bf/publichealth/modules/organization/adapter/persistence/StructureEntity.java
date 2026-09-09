package bf.publichealth.modules.organization.adapter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import bf.publichealth.modules.organization.domain.TypeStructure;

/**
 * Structure sanitaire de l'annuaire national (migration V12).
 *
 * <p>Désactivation SOFT uniquement ({@code active=false}) : l'annuaire
 * est la référence historique nationale, une structure fermée ne
 * disparaît jamais. {@code updated_at} est maintenu par
 * {@link PreUpdate} — les gardes de version restent applicatives
 * (service), la table porte la vérité.</p>
 */
@Entity
@Table(name = "structure", schema = "organization")
public class StructureEntity {

    /** UUID v7 — généré côté serveur (l'annuaire ne se crée pas hors ligne). */
    @Id
    private UUID id;

    /** Mnémonique officielle, unique (ex. CSPS-OUA-014). */
    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String nom;

    @Convert(converter = TypeStructureConverter.class)
    @Column(nullable = false, length = 16)
    private TypeStructure type;

    @Column(length = 100)
    private String region;

    @Column(length = 100)
    private String province;

    @Column(length = 100)
    private String commune;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StructureEntity() {
    }

    public StructureEntity(UUID id, String code, String nom, TypeStructure type,
                           String region, String province, String commune,
                           BigDecimal latitude, BigDecimal longitude) {
        this.id = id;
        this.code = code;
        this.nom = nom;
        this.type = type;
        this.region = region;
        this.province = province;
        this.commune = commune;
        this.latitude = latitude;
        this.longitude = longitude;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** PATCH : fusionne les champs renseignés (null = inchangé, P0). */
    public void mettreAJour(String nom, TypeStructure type, String region,
                            String province, String commune,
                            BigDecimal latitude, BigDecimal longitude) {
        if (nom != null) {
            this.nom = nom;
        }
        if (type != null) {
            this.type = type;
        }
        if (region != null) {
            this.region = region;
        }
        if (province != null) {
            this.province = province;
        }
        if (commune != null) {
            this.commune = commune;
        }
        if (latitude != null) {
            this.latitude = latitude;
        }
        if (longitude != null) {
            this.longitude = longitude;
        }
    }

    /** Désactivation / réactivation SOFT — l'état est déjà validé par le service. */
    public void changerActivation(boolean active) {
        this.active = active;
    }

    @PreUpdate
    void avantMiseAJour() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getNom() {
        return nom;
    }

    public TypeStructure getType() {
        return type;
    }

    public String getRegion() {
        return region;
    }

    public String getProvince() {
        return province;
    }

    public String getCommune() {
        return commune;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
