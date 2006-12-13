/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.PaddedEphemerallyEncryptedBucket;

public class ClientPut extends ClientPutBase {

	final ClientPutter putter;
	private final short uploadFrom;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	/** If uploadFrom==UPLOAD_FROM_REDIRECT, this is the target URI */
	private final FreenetURI targetURI;
	private final Bucket data;
	private final ClientMetadata clientMetadata;
	/** We store the size of inserted data before freeing it */
	private long finishedSize;
	/** Filename if the file has one */
	private final String targetFilename;
	
	/**
	 * Creates a new persistent insert.
	 * 
	 * @param uri
	 *            The URI to insert data to
	 * @param identifier
	 *            The identifier of the insert
	 * @param verbosity
	 *            The verbosity bitmask
	 * @param handler
	 *            The FCP connection handler
	 * @param priorityClass
	 *            The priority for this insert
	 * @param persistenceType
	 *            The persistence type of this insert
	 * @param clientToken
	 *            The client token of this insert
	 * @param global
	 *            Whether this insert appears on the global queue
	 * @param getCHKOnly
	 *            Whether only the resulting CHK is requested
	 * @param dontCompress
	 *            Whether the file should not be compressed
	 * @param maxRetries
	 *            The maximum number of retries
	 * @param uploadFromType
	 *            Where the file is uploaded from
	 * @param origFilename
	 *            The original filename
	 * @param contentType
	 *            The content type of the data
	 * @param data
	 *            The data (may be <code>null</code> if
	 *            <code>uploadFromType</code> is UPLOAD_FROM_DISK or
	 *            UPLOAD_FROM_REDIRECT)
	 * @param redirectTarget
	 *            The URI to redirect to (if <code>uploadFromType</code> is
	 *            UPLOAD_FROM_REDIRECT)
	 * @throws IdentifierCollisionException
	 */
	public ClientPut(FCPClient globalClient, FreenetURI uri, String identifier, int verbosity, 
			short priorityClass, short persistenceType, String clientToken, boolean getCHKOnly,
			boolean dontCompress, int maxRetries, short uploadFromType, File origFilename, String contentType,
			Bucket data, FreenetURI redirectTarget, String targetFilename, boolean earlyEncode) throws IdentifierCollisionException {
		super(uri, identifier, verbosity, null, globalClient, priorityClass, persistenceType, null, true, getCHKOnly, dontCompress, maxRetries, earlyEncode);
		this.targetFilename = targetFilename;
		this.uploadFrom = uploadFromType;
		this.origFilename = origFilename;
		// Now go through the fields one at a time
		String mimeType = contentType;
		this.clientToken = clientToken;
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, false);
		Bucket tempData = data;
		ClientMetadata cm = new ClientMetadata(mimeType);
		boolean isMetadata = false;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "data = "+tempData+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			this.targetURI = redirectTarget;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, targetURI, cm);
			cm = null;
			byte[] d;
			try {
				d = m.writeToByteArray();
			} catch (MetadataUnresolvedException e) {
				// Impossible
				Logger.error(this, "Impossible: "+e, e);
				onFailure(new InserterException(InserterException.INTERNAL_ERROR, "Impossible: "+e+" in ClientPut", null), null);
				this.data = null;
				clientMetadata = null;
				putter = null;
				return;
			}
			tempData = new SimpleReadOnlyArrayBucket(d);
			isMetadata = true;
		} else
			targetURI = null;
		this.data = tempData;
		this.clientMetadata = cm;
		if(logMINOR) Logger.minor(this, "data = "+data+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		putter = new ClientPutter(this, data, uri, cm, 
				ctx, client.core.requestStarters.chkPutScheduler, client.core.requestStarters.sskPutScheduler, priorityClass, 
				getCHKOnly, isMetadata, client.lowLevelClient, null, targetFilename);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
	}
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) throws IdentifierCollisionException {
		super(message.uri, message.identifier, message.verbosity, handler, 
				message.priorityClass, message.persistenceType, message.clientToken, message.global,
				message.getCHKOnly, message.dontCompress, message.maxRetries, message.earlyEncode);
		this.targetFilename = message.targetFilename;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.uploadFrom = message.uploadFromType;
		this.origFilename = message.origFilename;
		// Now go through the fields one at a time
		String mimeType = message.contentType;
		if(mimeType == null && origFilename != null) {
			mimeType = DefaultMIMETypes.guessMIMEType(origFilename.getName(), true);
		}
		if(mimeType == null) {
			mimeType = DefaultMIMETypes.guessMIMEType(identifier, true);
		}
		clientToken = message.clientToken;
		if(persistenceType != PERSIST_CONNECTION)
			client.register(this, false);
		Bucket tempData = message.bucket;
		ClientMetadata cm = new ClientMetadata(mimeType);
		boolean isMetadata = false;
		if(logMINOR) Logger.minor(this, "data = "+tempData+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			this.targetURI = message.redirectTarget;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, targetURI, cm);
			cm = null;
			byte[] d;
			try {
				d = m.writeToByteArray();
			} catch (MetadataUnresolvedException e) {
				// Impossible
				Logger.error(this, "Impossible: "+e, e);
				onFailure(new InserterException(InserterException.INTERNAL_ERROR, "Impossible: "+e+" in ClientPut", null), null);
				this.data = null;
				clientMetadata = null;
				putter = null;
				return;
			}
			tempData = new SimpleReadOnlyArrayBucket(d);
			isMetadata = true;
		} else
			targetURI = null;
		this.data = tempData;
		this.clientMetadata = cm;
		if(logMINOR) Logger.minor(this, "data = "+data+", uploadFrom = "+ClientPutMessage.uploadFromString(uploadFrom));
		putter = new ClientPutter(this, data, uri, cm, 
				ctx, client.core.requestStarters.chkPutScheduler, client.core.requestStarters.sskPutScheduler, priorityClass, 
				getCHKOnly, isMetadata, client.lowLevelClient, null, targetFilename);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
			if(handler != null && (!handler.isGlobalSubscribed()))
				handler.outputHandler.queue(msg);
		}
	}
	
	/**
	 * Create from a persisted SimpleFieldSet.
	 * Not very tolerant of errors, as the input was generated
	 * by the node.
	 * @throws PersistenceParseException 
	 * @throws IOException 
	 */
	public ClientPut(SimpleFieldSet fs, FCPClient client2) throws PersistenceParseException, IOException {
		super(fs, client2);
		String mimeType = fs.get("Metadata.ContentType");

		String from = fs.get("UploadFrom");
		
		if(from.equals("direct")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_DIRECT;
		} else if(from.equals("disk")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_DISK;
		} else if(from.equals("redirect")) {
			uploadFrom = ClientPutMessage.UPLOAD_FROM_REDIRECT;
		} else {
			// FIXME remove this back compatibility hack in a few builds' time
			String s = fs.get("FromDisk");
			if(s.equalsIgnoreCase("true"))
				uploadFrom = ClientPutMessage.UPLOAD_FROM_DISK;
			else if(s.equalsIgnoreCase("false"))
				uploadFrom = ClientPutMessage.UPLOAD_FROM_DIRECT;
			else
				throw new PersistenceParseException("Unknown UploadFrom: "+from);
		}
		
		ClientMetadata cm = new ClientMetadata(mimeType);
		
		boolean isMetadata = false;
		
		targetFilename = fs.get("TargetFilename");
		
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DISK) {
			origFilename = new File(fs.get("Filename"));
			data = new FileBucket(origFilename, true, false, false, false);
			targetURI = null;
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT) {
			origFilename = null;
			if(!finished) {
				byte[] key = HexUtil.hexToBytes(fs.get("TempBucket.DecryptKey"));
				String fnam = fs.get("TempBucket.Filename");
				long sz = Long.parseLong(fs.get("TempBucket.Size"));
				try {
					data = client.server.core.persistentTempBucketFactory.registerEncryptedBucket(fnam, key, sz);
					if(data.size() != sz)
						throw new PersistenceParseException("Size of bucket is wrong: "+data.size()+" should be "+sz);
				} catch (IOException e) {
					throw new PersistenceParseException("Could not read old bucket (should be "+sz+" bytes) for "+identifier);
				}
			} else data = null;
			targetURI = null;
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			String target = fs.get("TargetURI");
			targetURI = new FreenetURI(target);
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, targetURI, cm);
			cm = null;
			byte[] d;
			try {
				d = m.writeToByteArray();
			} catch (MetadataUnresolvedException e) {
				// Impossible
				Logger.error(this, "Impossible: "+e, e);
				onFailure(new InserterException(InserterException.INTERNAL_ERROR, "Impossible: "+e+" in ClientPut", null), null);
				this.data = null;
				clientMetadata = null;
				origFilename = null;
				putter = null;
				return;
			}
			data = new SimpleReadOnlyArrayBucket(d);
			origFilename = null;
			isMetadata = true;
		} else {
			throw new PersistenceParseException("shouldn't happen");
		}
		this.clientMetadata = cm;
		putter = new ClientPutter(this, data, uri, cm, ctx, client.core.requestStarters.chkPutScheduler, 
				client.core.requestStarters.sskPutScheduler, priorityClass, getCHKOnly, isMetadata, client.lowLevelClient, fs.subset("progress"), targetFilename);
		if(persistenceType != PERSIST_CONNECTION) {
			FCPMessage msg = persistentTagMessage();
			client.queueClientRequestMessage(msg, 0);
		}
		
	}

	public void start() {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting "+this+" : "+identifier);
		if(finished) return;
		try {
			putter.start(earlyEncode);
			started = true;
			if(persistenceType != PERSIST_CONNECTION && !finished) {
				FCPMessage msg = persistentTagMessage();
				client.queueClientRequestMessage(msg, 0);
			}
		} catch (InserterException e) {
			started = true;
			onFailure(e, null);
		}
	}

	protected void freeData() {
		if(data == null) return;
		finishedSize=data.size();
		data.free();
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		// This is all fixed, so no need for synchronization.
		fs.put("Metadata.ContentType", clientMetadata.getMIMEType());
		fs.put("UploadFrom", ClientPutMessage.uploadFromString(uploadFrom));
		if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DISK) {
			fs.put("Filename", origFilename.getPath());
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT) {
			if(!finished) {
				// the bucket is a persistent encrypted temp bucket
				PaddedEphemerallyEncryptedBucket bucket = (PaddedEphemerallyEncryptedBucket) data;
				fs.put("TempBucket.DecryptKey", HexUtil.bytesToHex(bucket.getKey()));
				fs.put("TempBucket.Filename", ((FileBucket)(bucket.getUnderlying())).getName());
				fs.put("TempBucket.Size", Long.toString(bucket.size()));
			}
		} else if(uploadFrom == ClientPutMessage.UPLOAD_FROM_REDIRECT) {
			fs.put("TargetURI", targetURI.toString());
		}
		if(putter != null)  {
			SimpleFieldSet sfs = putter.getProgressFieldset();
			fs.put("progress", sfs);
		}
		if(targetFilename != null)
			fs.put("TargetFilename", targetFilename);
		fs.put("EarlyEncode", Boolean.toString(earlyEncode));
		return fs;
	}

	protected freenet.client.async.ClientRequester getClientRequest() {
		return putter;
	}

	protected FCPMessage persistentTagMessage() {
		return new PersistentPut(identifier, uri, verbosity, priorityClass, uploadFrom, targetURI, 
				persistenceType, origFilename, clientMetadata.getMIMEType(), client.isGlobalQueue,
				getDataSize(), clientToken, started, ctx.maxInsertRetries, targetFilename);
	}

	protected String getTypeName() {
		return "PUT";
	}

	public boolean hasSucceeded() {
		return succeeded;
	}

	public FreenetURI getFinalURI() {
		return generatedURI;
	}

	public boolean isDirect() {
		return uploadFrom == ClientPutMessage.UPLOAD_FROM_DIRECT;
	}

	public File getOrigFilename() {
		if(uploadFrom != ClientPutMessage.UPLOAD_FROM_DISK)
			return null;
		return origFilename;
	}

	public long getDataSize() {
		if(data == null)
			return finishedSize;
		else
			return data.size();
	}

	public String getMIMEType() {
		return clientMetadata.getMIMEType();
	}

	public boolean canRestart() {
		if(!finished) {
			Logger.minor(this, "Cannot restart because not finished for "+identifier);
			return false;
		}
		if(succeeded) {
			Logger.minor(this, "Cannot restart because succeeded for "+identifier);
			return false;
		}
		return putter.canRestart();
	}

	public boolean restart() {
		if(!canRestart()) return false;
		setVarsRestart();
		try {
			if(putter.restart(earlyEncode)) {
				synchronized(this) {
					generatedURI = null;
					started = true;
				}
			}
			return true;
		} catch (InserterException e) {
			onFailure(e, null);
			return false;
		}
	}

	public void onFailure(FetchException e, ClientGetter state) {}

	public void onSuccess(FetchResult result, ClientGetter state) {}
}
