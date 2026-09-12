package bf.publichealth.modules.prescription.adapter.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.prescription.application.PrescriptionService;
import jakarta.validation.Valid;

/**
 * API prescription & dispensation — /api/v1/prescriptions.
 *
 * <p>Création idempotente par clientRequestId (rejeu = 200, la même
 * prescription, jamais deux). Annulation et contre-entrée par motif
 * OBLIGATOIRE. Dispensation partielle cumulée : le dépassement renvoie
 * 409 avec {restant, demande} — le comptoir corrige sans deviner.</p>
 */
@RestController
@RequestMapping("/api/v1/prescriptions")
public class PrescriptionController {

    private final PrescriptionService prescriptionService;

    public PrescriptionController(PrescriptionService prescriptionService) {
        this.prescriptionService = prescriptionService;
    }

    /** Création : 201 + Location ; rejeu idempotent = 200, la même prescription. */
    @PostMapping
    public ResponseEntity<PrescriptionDtos.PrescriptionResponse> creer(
            @Valid @RequestBody PrescriptionDtos.CreatePrescriptionRequest request) {

        var creation = prescriptionService.creer(new PrescriptionService.CommandeCreation(
                request.id(), request.patientId(), request.encounterId(), request.prescriberId(),
                request.facilityId(), request.clientRequestId(), request.createdVia(),
                request.issuedAt(),
                request.items().stream()
                        .map(i -> new PrescriptionService.LigneCommande(
                                i.medicationCode(), i.medicationLabel(), i.dose(), i.form(),
                                i.route(), i.frequency(), i.durationDays(),
                                i.quantityPrescribed()))
                        .toList()));

        PrescriptionDtos.PrescriptionResponse corps =
                PrescriptionDtos.PrescriptionResponse.detail(creation.detail());
        if (creation.rejouee()) {
            return ResponseEntity.ok(corps);
        }
        return ResponseEntity
                .created(URI.create("/api/v1/prescriptions/" + corps.id()))
                .body(corps);
    }

    /** Vue complète : lignes (cumul, restant) + dispensations cumulées. */
    @GetMapping("/{id}")
    public ResponseEntity<PrescriptionDtos.PrescriptionResponse> trouver(@PathVariable UUID id) {
        return ResponseEntity.ok(
                PrescriptionDtos.PrescriptionResponse.detail(prescriptionService.trouver(id)));
    }

    /** Dossier pharmacologique d'un patient, la plus récente d'abord. */
    @GetMapping
    public ResponseEntity<?> lister(@RequestParam(required = false) UUID patientId) {
        if (patientId == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Paramètre patientId requis : GET /api/v1/prescriptions?patientId=");
            problem.setTitle("Requête incomplète");
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        List<PrescriptionDtos.PrescriptionResponse> prescriptions =
                prescriptionService.listerParPatient(patientId).stream()
                        .map(PrescriptionDtos.PrescriptionResponse::resume)
                        .toList();
        return ResponseEntity.ok(prescriptions);
    }

    /** Annulation logistique : seul active → cancelled, motif OBLIGATOIRE. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<PrescriptionDtos.PrescriptionResponse> annuler(
            @PathVariable UUID id,
            @Valid @RequestBody PrescriptionDtos.CancelRequest request) {
        return ResponseEntity.ok(PrescriptionDtos.PrescriptionResponse.detail(
                prescriptionService.annuler(id, request.reason())));
    }

    /** Contre-entrée d'erreur clinique (entered-in-error) — jamais de DELETE. */
    @PostMapping("/{id}/entered-in-error")
    public ResponseEntity<PrescriptionDtos.PrescriptionResponse> passerEnErreur(
            @PathVariable UUID id,
            @Valid @RequestBody PrescriptionDtos.CancelRequest request) {
        return ResponseEntity.ok(PrescriptionDtos.PrescriptionResponse.detail(
                prescriptionService.passerEnErreur(id, request.reason())));
    }

    /**
     * Dispensation partielle d'une ligne : 201 ; rejeu idempotent = 200.
     * Dépassement → 409 {restant, demande} ; prescription inactive → 409 ;
     * RUPTURE DE STOCK (V14, I8) → 409 {disponible} — la pharmacie ne
     * dispenser plus ce qui n'existe pas.
     */
    @PostMapping("/{id}/items/{itemId}/dispense")
    public ResponseEntity<?> dispenser(
            @PathVariable UUID id,
            @PathVariable UUID itemId,
            @Valid @RequestBody PrescriptionDtos.DispenseRequest request) {

        try {
            PrescriptionService.DispensationResultat resultat = prescriptionService.dispenser(
                    id, itemId, request.quantity(), request.clientRequestId(), request.dispensedBy());
            return ResponseEntity
                    .status(resultat.rejouee() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(PrescriptionDtos.DispenseResponse.from(resultat));
        } catch (bf.publichealth.modules.pharmacie.application.StockService.RuptureStockException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    e.getMessage());
            problem.setTitle("Rupture de stock");
            problem.setProperty("disponible", e.getDisponible());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
    }
}
