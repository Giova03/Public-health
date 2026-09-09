package bf.publichealth.modules.payments.adapter.web;

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

import bf.publichealth.modules.payments.application.InvoiceService;
import jakarta.validation.Valid;

/**
 * API facturation — /api/v1/invoices (épique E4).
 *
 * <p>Création idempotente par clientRequestId (rejeu = 200, la MÊME
 * facture, jamais deux). Le total est calculé serveur depuis les
 * lignes. Émission (draft→issued) et annulation terminale (motif
 * OBLIGATOIRE) : toute transition illégale renvoie 409, l'historique
 * reste intact.</p>
 */
@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    /** Création : 201 + Location ; rejeu idempotent = 200, la même facture. */
    @PostMapping
    public ResponseEntity<InvoiceDtos.InvoiceResponse> creer(
            @Valid @RequestBody InvoiceDtos.CreateInvoiceRequest request) {

        var creation = invoiceService.creer(new InvoiceService.CommandeCreation(
                request.patientId(), request.encounterId(), request.currency(),
                request.clientRequestId(), request.createdBy(),
                request.items().stream()
                        .map(i -> new InvoiceService.LigneCommande(
                                i.label(), i.quantity(), i.unitPrice()))
                        .toList()));

        InvoiceDtos.InvoiceResponse corps = InvoiceDtos.InvoiceResponse.from(creation.detail());
        if (creation.rejouee()) {
            return ResponseEntity.ok(corps);
        }
        return ResponseEntity
                .created(URI.create("/api/v1/invoices/" + corps.id()))
                .body(corps);
    }

    /** Vue complète : lignes + cumul encaisse (rapprochement). */
    @GetMapping("/{id}")
    public ResponseEntity<InvoiceDtos.InvoiceResponse> trouver(@PathVariable UUID id) {
        return ResponseEntity.ok(InvoiceDtos.InvoiceResponse.from(invoiceService.trouver(id)));
    }

    /** Factures d'un patient, la plus récente d'abord (paramètre requis). */
    @GetMapping
    public ResponseEntity<?> lister(@RequestParam(required = false) UUID patientId) {
        if (patientId == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Paramètre patientId requis : GET /api/v1/invoices?patientId=");
            problem.setTitle("Requête incomplète");
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        List<InvoiceDtos.InvoiceResume> factures = invoiceService.listerParPatient(patientId)
                .stream()
                .map(InvoiceDtos.InvoiceResume::from)
                .toList();
        return ResponseEntity.ok(factures);
    }

    /** Émission : seul draft → issued, horodatée. Corps optionnel {issuedBy}. */
    @PostMapping("/{id}/issue")
    public ResponseEntity<InvoiceDtos.InvoiceResponse> emettre(
            @PathVariable UUID id,
            @RequestBody(required = false) InvoiceDtos.IssueRequest request) {
        UUID acteur = request == null ? null : request.issuedBy();
        return ResponseEntity.ok(InvoiceDtos.InvoiceResponse.from(
                invoiceService.emettre(id, acteur)));
    }

    /** Annulation TERMINALE : draft|issued → voided, motif OBLIGATOIRE. */
    @PostMapping("/{id}/void")
    public ResponseEntity<InvoiceDtos.InvoiceResponse> annuler(
            @PathVariable UUID id,
            @Valid @RequestBody InvoiceDtos.VoidRequest request) {
        return ResponseEntity.ok(InvoiceDtos.InvoiceResponse.from(
                invoiceService.annuler(id, request.reason(), request.voidedBy())));
    }
}
