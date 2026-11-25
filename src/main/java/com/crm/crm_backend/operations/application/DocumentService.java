package com.crm.crm_backend.operations.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.crm.crm_backend.operations.api.dto.OpsDtos.DraftRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.DraftResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FileResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FileTextResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FileUpdateRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FolderRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FolderResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FolderUpdateRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.TextDocumentRequest;
import com.crm.crm_backend.operations.domain.OpsFile;
import com.crm.crm_backend.operations.domain.OpsFileBlob;
import com.crm.crm_backend.operations.domain.OpsFolder;
import com.crm.crm_backend.operations.infrastructure.OpsFileBlobRepository;
import com.crm.crm_backend.operations.infrastructure.OpsFileRepository;
import com.crm.crm_backend.operations.infrastructure.OpsFolderRepository;
import com.crm.crm_backend.operations.infrastructure.VendorRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * Folders and files for the document vault (staff and provider documents), vendor file cabinets,
 * practice resources and training. Everyone can read resources and training; the vault needs the
 * staff or credentialing permission and vendor files the vendor permission.
 */
@Service
public class DocumentService {

	public static final Set<String> SPACES = Set.of("VAULT", "VENDOR", "RESOURCES", "TRAINING");
	static final long MAX_BYTES = 10L * 1024 * 1024;

	private final OpsSupport ops;
	private final OpsDefaultsService defaults;
	private final OpsFolderRepository folderRepository;
	private final OpsFileRepository fileRepository;
	private final OpsFileBlobRepository blobRepository;
	private final VendorRepository vendorRepository;
	private final DraftWriter draftWriter;

	public DocumentService(
			OpsSupport ops,
			OpsDefaultsService defaults,
			OpsFolderRepository folderRepository,
			OpsFileRepository fileRepository,
			OpsFileBlobRepository blobRepository,
			VendorRepository vendorRepository,
			DraftWriter draftWriter
	) {
		this.ops = ops;
		this.defaults = defaults;
		this.folderRepository = folderRepository;
		this.fileRepository = fileRepository;
		this.blobRepository = blobRepository;
		this.vendorRepository = vendorRepository;
		this.draftWriter = draftWriter;
	}

	/* ── permissions ─────────────────────────────────────────────────── */

	void requireRead(UserPrincipal principal, String space) {
		switch (space) {
			case "VAULT" -> ops.requireAny(principal, OpsSupport.STAFF, OpsSupport.CREDS);
			case "VENDOR" -> ops.require(principal, OpsSupport.VENDORS);
			default -> ops.require(principal, OpsSupport.DASH);
		}
	}

	void requireWrite(UserPrincipal principal, String space) {
		switch (space) {
			case "VAULT" -> ops.requireAny(principal, OpsSupport.STAFF, OpsSupport.CREDS);
			case "VENDOR" -> ops.require(principal, OpsSupport.VENDORS);
			default -> ops.requireAny(principal, OpsSupport.STAFF, OpsSupport.SETTINGS);
		}
	}

	public boolean canWrite(UserPrincipal principal, String space) {
		try {
			requireWrite(principal, space(space));
			return true;
		} catch (ApiException ex) {
			return false;
		}
	}

	/* ── folders ─────────────────────────────────────────────────────── */

	@Transactional
	public List<FolderResponse> folders(UserPrincipal principal, String spaceIn, UUID vendorId) {
		String space = space(spaceIn);
		if ("VAULT".equals(space)) {
			throw ApiException.badRequest("The document vault is organized by person and category, not folders.");
		}
		requireRead(principal, space);
		if ("VENDOR".equals(space)) {
			requireVendor(vendorId);
			defaults.ensureVendorFolders(ops.orgId(), vendorId);
			return folderRepository.findByOrganizationIdAndSpaceAndVendorIdOrderBySortOrderAscNameAsc(ops.orgId(), space, vendorId).stream().map(this::toFolder).toList();
		}
		defaults.ensureLibrary(ops.orgId(), space);
		return folderRepository.findByOrganizationIdAndSpaceAndVendorIdIsNullOrderBySortOrderAscNameAsc(ops.orgId(), space).stream().map(this::toFolder).toList();
	}

	@Transactional
	public FolderResponse createFolder(UserPrincipal principal, FolderRequest r) {
		String space = space(r.space());
		requireWrite(principal, space);
		if ("VAULT".equals(space)) {
			throw ApiException.badRequest("The document vault does not use folders.");
		}
		if ("VENDOR".equals(space)) {
			requireVendor(r.vendorId());
		}
		OpsFolder parent = r.parentId() == null ? null : requireFolder(r.parentId());
		if (parent != null && (!parent.getSpace().equals(space) || !Objects.equals(parent.getVendorId(), "VENDOR".equals(space) ? r.vendorId() : null))) {
			throw ApiException.badRequest("The parent folder is somewhere else.");
		}
		OpsFolder f = new OpsFolder();
		f.setSpace(space);
		f.setVendorId("VENDOR".equals(space) ? r.vendorId() : null);
		f.setParentId(parent == null ? null : parent.getId());
		f.setName(r.name().trim());
		f.setSortOrder(1000);
		return toFolder(folderRepository.save(f));
	}

	@Transactional
	public FolderResponse updateFolder(UserPrincipal principal, UUID id, FolderUpdateRequest r) {
		OpsFolder f = requireFolder(id);
		requireWrite(principal, f.getSpace());
		if (OpsSupport.clean(r.name()) != null) {
			if (f.isLocked()) {
				throw ApiException.badRequest("This folder is built in and cannot be renamed.");
			}
			f.setName(r.name().trim());
		}
		if (Boolean.TRUE.equals(r.moveToTop())) {
			f.setParentId(null);
		} else if (r.parentId() != null) {
			OpsFolder target = requireFolder(r.parentId());
			if (!target.getSpace().equals(f.getSpace()) || !Objects.equals(target.getVendorId(), f.getVendorId())) {
				throw ApiException.badRequest("Folders can only move within the same library.");
			}
			Set<UUID> seen = new HashSet<>();
			for (OpsFolder cur = target; cur != null; cur = cur.getParentId() == null ? null : folderRepository.findByIdAndOrganizationId(cur.getParentId(), ops.orgId()).orElse(null)) {
				if (cur.getId().equals(f.getId()) || !seen.add(cur.getId())) {
					throw ApiException.badRequest("A folder cannot move inside itself.");
				}
			}
			f.setParentId(target.getId());
		}
		return toFolder(folderRepository.save(f));
	}

	@Transactional
	public void deleteFolder(UserPrincipal principal, UUID id) {
		OpsFolder f = requireFolder(id);
		requireWrite(principal, f.getSpace());
		if (f.isLocked()) {
			throw ApiException.badRequest("This folder is built in and cannot be deleted.");
		}
		folderRepository.delete(f);
	}

	/* ── files ───────────────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public List<FileResponse> files(UserPrincipal principal, String spaceIn, UUID vendorId, UUID staffId) {
		String space = space(spaceIn);
		requireRead(principal, space);
		List<OpsFile> list;
		if (staffId != null) {
			list = fileRepository.findByOrganizationIdAndStaffIdOrderByCreatedAtDesc(ops.orgId(), staffId).stream().filter(f -> f.getSpace().equals(space)).toList();
		} else if ("VENDOR".equals(space)) {
			list = vendorId == null
					? fileRepository.findByOrganizationIdAndSpaceOrderByCreatedAtDesc(ops.orgId(), space)
					: fileRepository.findByOrganizationIdAndSpaceAndVendorIdOrderByCreatedAtDesc(ops.orgId(), space, vendorId);
		} else {
			list = fileRepository.findByOrganizationIdAndSpaceOrderByCreatedAtDesc(ops.orgId(), space);
		}
		return list.stream().map(this::toFile).toList();
	}

	@Transactional
	public FileResponse upload(UserPrincipal principal, String spaceIn, MultipartFile file, UUID folderId, UUID vendorId, UUID staffId,
			String category, String docType, LocalDate expiresOn, String notes, String linkRef) {
		String space = space(spaceIn);
		requireWrite(principal, space);
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("Choose a file to upload.");
		}
		if (file.getSize() > MAX_BYTES) {
			throw ApiException.badRequest("Files can be up to 10 MB.");
		}
		OpsFile f = new OpsFile();
		place(f, space, folderId, vendorId, staffId);
		String name = OpsSupport.clean(file.getOriginalFilename());
		f.setName(name == null ? "document" : name.replaceAll("[\\\\/]", "_"));
		f.setContentType(OpsSupport.clean(file.getContentType()) == null ? "application/octet-stream" : file.getContentType());
		f.setSizeBytes(file.getSize());
		f.setCategory(OpsSupport.clean(category));
		f.setDocType(OpsSupport.clean(docType));
		f.setExpiresOn(expiresOn);
		f.setNotes(OpsSupport.clean(notes));
		f.setLinkRef(OpsSupport.clean(linkRef));
		f.setUploadedBy(ops.actorName(principal));
		f = fileRepository.save(f);
		OpsFileBlob blob = new OpsFileBlob();
		blob.setFileId(f.getId());
		try {
			blob.setData(file.getBytes());
		} catch (IOException ex) {
			throw ApiException.badRequest("Could not read the uploaded file.");
		}
		blobRepository.save(blob);
		return toFile(f);
	}

	@Transactional
	public FileResponse createText(UserPrincipal principal, TextDocumentRequest r) {
		String space = space(r.space());
		requireWrite(principal, space);
		OpsFile f = new OpsFile();
		place(f, space, r.folderId(), r.vendorId(), null);
		f.setName(r.name().trim());
		f.setContentType("text/plain");
		f.setBodyText(r.text());
		f.setSizeBytes((long) r.text().getBytes(StandardCharsets.UTF_8).length);
		f.setDocType(OpsSupport.clean(r.docType()));
		f.setUploadedBy(ops.actorName(principal));
		return toFile(fileRepository.save(f));
	}

	@Transactional
	public FileResponse update(UserPrincipal principal, UUID id, FileUpdateRequest r) {
		OpsFile f = requireFile(id);
		requireWrite(principal, f.getSpace());
		if (Boolean.TRUE.equals(r.moveToTop())) {
			f.setFolderId(null);
		} else if (r.folderId() != null) {
			OpsFolder folder = requireFolder(r.folderId());
			if (!folder.getSpace().equals(f.getSpace()) || !Objects.equals(folder.getVendorId(), f.getVendorId())) {
				throw ApiException.badRequest("Files can only move within the same library.");
			}
			f.setFolderId(folder.getId());
		}
		if (OpsSupport.clean(r.name()) != null) {
			f.setName(r.name().trim());
		}
		if (r.category() != null) {
			f.setCategory(OpsSupport.clean(r.category()));
		}
		if (r.docType() != null) {
			f.setDocType(OpsSupport.clean(r.docType()));
		}
		if (Boolean.TRUE.equals(r.clearExpiry())) {
			f.setExpiresOn(null);
		} else if (r.expiresOn() != null) {
			f.setExpiresOn(r.expiresOn());
		}
		if (r.notes() != null) {
			f.setNotes(OpsSupport.clean(r.notes()));
		}
		if (r.linkRef() != null) {
			f.setLinkRef(OpsSupport.clean(r.linkRef()));
		}
		if (r.staffId() != null && "VAULT".equals(f.getSpace())) {
			f.setStaffId(ops.staff(r.staffId()).getId());
		}
		if (r.text() != null) {
			if (f.getBodyText() == null) {
				throw ApiException.badRequest("Only written documents can be edited here.");
			}
			f.setBodyText(r.text());
			f.setSizeBytes((long) r.text().getBytes(StandardCharsets.UTF_8).length);
		}
		return toFile(fileRepository.save(f));
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		OpsFile f = requireFile(id);
		requireWrite(principal, f.getSpace());
		fileRepository.delete(f);
	}

	/** Metadata plus bytes for download. Written documents download as plain text. */
	@Transactional(readOnly = true)
	public Download download(UserPrincipal principal, UUID id) {
		OpsFile f = requireFile(id);
		requireRead(principal, f.getSpace());
		if (f.getBodyText() != null) {
			String name = f.getName().toLowerCase().endsWith(".txt") ? f.getName() : f.getName() + ".txt";
			return new Download(name, "text/plain; charset=utf-8", f.getBodyText().getBytes(StandardCharsets.UTF_8));
		}
		byte[] data = blobRepository.findById(f.getId()).map(OpsFileBlob::getData)
				.orElseThrow(() -> ApiException.notFound("This file has no content."));
		return new Download(f.getName(), f.getContentType() == null ? "application/octet-stream" : f.getContentType(), data);
	}

	@Transactional(readOnly = true)
	public FileTextResponse text(UserPrincipal principal, UUID id) {
		OpsFile f = requireFile(id);
		requireRead(principal, f.getSpace());
		if (f.getBodyText() == null) {
			throw ApiException.badRequest("This is an uploaded file. Download it to view.");
		}
		return new FileTextResponse(f.getId(), f.getName(), f.getBodyText());
	}

	public DraftResponse draft(UserPrincipal principal, DraftRequest r) {
		String space = space(r.space());
		if (!"TRAINING".equals(space) && !"RESOURCES".equals(space)) {
			throw ApiException.badRequest("Drafts are for training and resources.");
		}
		requireWrite(principal, space);
		return draftWriter.write(r);
	}

	public record Download(String name, String contentType, byte[] data) {
	}

	/* ── helpers ─────────────────────────────────────────────────────── */

	private void place(OpsFile f, String space, UUID folderId, UUID vendorId, UUID staffId) {
		f.setSpace(space);
		if ("VAULT".equals(space)) {
			f.setStaffId(ops.staff(staffId).getId());
			return;
		}
		if ("VENDOR".equals(space)) {
			requireVendor(vendorId);
			f.setVendorId(vendorId);
		}
		if (folderId != null) {
			OpsFolder folder = requireFolder(folderId);
			if (!folder.getSpace().equals(space) || !Objects.equals(folder.getVendorId(), f.getVendorId())) {
				throw ApiException.badRequest("That folder belongs to a different library.");
			}
			f.setFolderId(folder.getId());
		}
	}

	private static String space(String space) {
		String s = space == null ? "" : space.trim().toUpperCase();
		if (!SPACES.contains(s)) {
			throw ApiException.badRequest("Unknown library: " + space);
		}
		return s;
	}

	private void requireVendor(UUID vendorId) {
		if (vendorId == null || vendorRepository.findByIdAndOrganizationId(vendorId, ops.orgId()).isEmpty()) {
			throw ApiException.notFound("Vendor not found.");
		}
	}

	private OpsFolder requireFolder(UUID id) {
		return folderRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("Folder not found."));
	}

	private OpsFile requireFile(UUID id) {
		return fileRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("File not found."));
	}

	private FolderResponse toFolder(OpsFolder f) {
		return new FolderResponse(f.getId(), f.getSpace(), f.getVendorId(), f.getParentId(), f.getName(), f.isLocked());
	}

	private FileResponse toFile(OpsFile f) {
		return new FileResponse(f.getId(), f.getSpace(), f.getFolderId(), f.getVendorId(), f.getStaffId(), f.getCategory(), f.getDocType(),
				f.getName(), f.getContentType(), f.getSizeBytes(), f.getBodyText() != null, f.getExpiresOn(), f.getNotes(), f.getLinkRef(),
				f.getUploadedBy(), f.getCreatedAt(), f.getUpdatedAt());
	}
}
