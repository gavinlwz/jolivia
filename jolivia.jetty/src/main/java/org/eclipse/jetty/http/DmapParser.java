package org.eclipse.jetty.http;

import static org.eclipse.jetty.http.HttpCompliance.LEGACY;
import static org.eclipse.jetty.http.HttpCompliance.RFC2616;
import static org.eclipse.jetty.http.HttpCompliance.RFC7230;
import static org.eclipse.jetty.http.HttpTokens.CARRIAGE_RETURN;
import static org.eclipse.jetty.http.HttpTokens.LINE_FEED;
import static org.eclipse.jetty.http.HttpTokens.SPACE;
import static org.eclipse.jetty.http.HttpTokens.TAB;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class DmapParser extends HttpParser{

	 private static final Logger LOG = Log.getLogger(HttpParser.class);
	    @Deprecated
	    public final static String __STRICT="org.eclipse.jetty.http.HttpParser.STRICT";
	    public final static int INITIAL_URI_LENGTH=256;

	    /**
	     * Cache of common {@link HttpField}s including: <UL>
	     * <LI>Common static combinations such as:<UL>
	     *   <li>Connection: close
	     *   <li>Accept-Encoding: gzip
	     *   <li>Content-Length: 0
	     * </ul>
	     * <li>Combinations of Content-Type header for common mime types by common charsets
	     * <li>Most common headers with null values so that a lookup will at least
	     * determine the header name even if the name:value combination is not cached
	     * </ul>
	     */
	    public final static Trie<HttpField> CACHE = new ArrayTrie<>(2048);

	    private final static EnumSet<State> __idleStates = EnumSet.of(State.START,State.END,State.CLOSE,State.CLOSED);
	    private final static EnumSet<State> __completeStates = EnumSet.of(State.END,State.CLOSE,State.CLOSED);

	    private final boolean DEBUG=LOG.isDebugEnabled(); // Cache debug to help branch prediction
	    private final HttpHandler _handler;
	    private final RequestHandler _requestHandler;
	    private final ResponseHandler _responseHandler;
	    private final ComplianceHandler _complianceHandler;
	    private final int _maxHeaderBytes;
	    private final HttpCompliance _compliance;
	    private HttpField _field;
	    private HttpHeader _header;
	    private String _headerString;
	    private HttpHeaderValue _value;
	    private String _valueString;
	    private int _responseStatus;
	    private int _headerBytes;
	    private boolean _host;

	    /* ------------------------------------------------------------------------------- */
	    private volatile State _state=State.START;
	    private volatile boolean _eof;
	    private HttpMethod _method;
	    private String _methodString;
	    private HttpVersion _version;
	    private final Utf8StringBuilder _uri=new Utf8StringBuilder(INITIAL_URI_LENGTH); // Tune?
	    private EndOfContent _endOfContent;
	    private long _contentLength;
	    private long _contentPosition;
	    private int _chunkLength;
	    private int _chunkPosition;
	    private boolean _headResponse;
	    private boolean _cr;
	    private ByteBuffer _contentChunk;
	    private Trie<HttpField> _connectionFields;

	    private int _length;
	    private final StringBuilder _string=new StringBuilder();
		private final static CharState[] __charState;

	    static
	    {
	        CACHE.put(new HttpField(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE));
	        CACHE.put(new HttpField(HttpHeader.CONNECTION,HttpHeaderValue.KEEP_ALIVE));
	        CACHE.put(new HttpField(HttpHeader.CONNECTION,HttpHeaderValue.UPGRADE));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING,"gzip"));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING,"gzip, deflate"));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING,"gzip,deflate,sdch"));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT_LANGUAGE,"en-US,en;q=0.5"));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT_LANGUAGE,"en-GB,en-US;q=0.8,en;q=0.6"));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT_CHARSET,"ISO-8859-1,utf-8;q=0.7,*;q=0.3"));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT,"*/*"));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT,"image/png,image/*;q=0.8,*/*;q=0.5"));
	        CACHE.put(new HttpField(HttpHeader.ACCEPT,"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
	        CACHE.put(new HttpField(HttpHeader.PRAGMA,"no-cache"));
	        CACHE.put(new HttpField(HttpHeader.CACHE_CONTROL,"private, no-cache, no-cache=Set-Cookie, proxy-revalidate"));
	        CACHE.put(new HttpField(HttpHeader.CACHE_CONTROL,"no-cache"));
	        CACHE.put(new HttpField(HttpHeader.CONTENT_LENGTH,"0"));
	        CACHE.put(new HttpField(HttpHeader.CONTENT_ENCODING,"gzip"));
	        CACHE.put(new HttpField(HttpHeader.CONTENT_ENCODING,"deflate"));
	        CACHE.put(new HttpField(HttpHeader.TRANSFER_ENCODING,"chunked"));
	        CACHE.put(new HttpField(HttpHeader.EXPIRES,"Fri, 01 Jan 1990 00:00:00 GMT"));

	        // Add common Content types as fields
	        for (final String type : new String[]{"text/plain","text/html","text/xml","text/json","application/json","application/x-www-form-urlencoded"})
	        {
	            final HttpField field=new PreEncodedHttpField(HttpHeader.CONTENT_TYPE,type);
	            CACHE.put(field);

	            for (final String charset : new String[]{"utf-8","iso-8859-1"})
	            {
	                CACHE.put(new PreEncodedHttpField(HttpHeader.CONTENT_TYPE,type+";charset="+charset));
	                CACHE.put(new PreEncodedHttpField(HttpHeader.CONTENT_TYPE,type+"; charset="+charset));
	                CACHE.put(new PreEncodedHttpField(HttpHeader.CONTENT_TYPE,type+";charset="+charset.toUpperCase(Locale.ENGLISH)));
	                CACHE.put(new PreEncodedHttpField(HttpHeader.CONTENT_TYPE,type+"; charset="+charset.toUpperCase(Locale.ENGLISH)));
	            }
	        }

	        // Add headers with null values so HttpParser can avoid looking up name again for unknown values
	        for (final HttpHeader h:HttpHeader.values())
	            if (!CACHE.put(new HttpField(h,(String)null)))
	                throw new IllegalStateException("CACHE FULL");
	        // Add some more common headers
	        CACHE.put(new HttpField(HttpHeader.REFERER,(String)null));
	        CACHE.put(new HttpField(HttpHeader.IF_MODIFIED_SINCE,(String)null));
	        CACHE.put(new HttpField(HttpHeader.IF_NONE_MATCH,(String)null));
	        CACHE.put(new HttpField(HttpHeader.AUTHORIZATION,(String)null));
	        CACHE.put(new HttpField(HttpHeader.COOKIE,(String)null));
	    }

	    /* ------------------------------------------------------------------------------- */
	    public DmapParser(final RequestHandler handler)
	    {
	        this(handler,-1,compliance());
	    }

	    /* ------------------------------------------------------------------------------- */
	    public DmapParser(final ResponseHandler handler)
	    {
	        this(handler,-1,compliance());
	    }

	    /* ------------------------------------------------------------------------------- */
	    public DmapParser(final RequestHandler handler,final int maxHeaderBytes)
	    {
	        this(handler,maxHeaderBytes,compliance());
	    }

	    /* ------------------------------------------------------------------------------- */
	    public DmapParser(final ResponseHandler handler,final int maxHeaderBytes)
	    {
	        this(handler,maxHeaderBytes,compliance());
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Deprecated
	    public DmapParser(final RequestHandler handler,final int maxHeaderBytes,final boolean strict)
	    {
	        this(handler,maxHeaderBytes,strict?HttpCompliance.LEGACY:compliance());
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Deprecated
	    public DmapParser(final ResponseHandler handler,final int maxHeaderBytes,final boolean strict)
	    {
	        this(handler,maxHeaderBytes,strict?HttpCompliance.LEGACY:compliance());
	    }

	    /* ------------------------------------------------------------------------------- */
	    public DmapParser(final RequestHandler handler,final HttpCompliance compliance)
	    {
	        this(handler,-1,compliance);
	    }

	    /* ------------------------------------------------------------------------------- */
	    public DmapParser(final RequestHandler handler,final int maxHeaderBytes,final HttpCompliance compliance)
	    {
	    	super(handler,maxHeaderBytes, compliance);
	        _handler=handler;
	        _requestHandler=handler;
	        _responseHandler=null;
	        _maxHeaderBytes=maxHeaderBytes;
	        _compliance=compliance==null?compliance():compliance;
	        _complianceHandler=(ComplianceHandler)(handler instanceof ComplianceHandler?handler:null);
	    }

	    /* ------------------------------------------------------------------------------- */
	    public DmapParser(final ResponseHandler handler,final int maxHeaderBytes,final HttpCompliance compliance)
	    {
	    	super(handler,maxHeaderBytes, compliance);
	        _handler=handler;
	        _requestHandler=null;
	        _responseHandler=handler;
	        _maxHeaderBytes=maxHeaderBytes;
	        _compliance=compliance==null?compliance():compliance;
	        _complianceHandler=(ComplianceHandler)(handler instanceof ComplianceHandler?handler:null);
	    }

		private static HttpCompliance compliance() {
			final Boolean strict = Boolean.getBoolean(__STRICT);
			return strict ? HttpCompliance.LEGACY : HttpCompliance.RFC7230;
		}

		/* ------------------------------------------------------------------------------- */
	    /** Check RFC compliance violation
	     * @param compliance The compliance level violated
	     * @param reason The reason for the violation
	     * @return True if the current compliance level is set so as to Not allow this violation
	     */
	    @Override
		protected boolean complianceViolation(final HttpCompliance compliance,final String reason)
	    {
	        if (_complianceHandler==null)
	            return _compliance.ordinal()>=compliance.ordinal();
	        if (_compliance.ordinal()<compliance.ordinal())
	        {
	            _complianceHandler.onComplianceViolation(_compliance,compliance,reason);
	            return false;
	        }
	        return true;
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		protected String legacyString(final String orig, final String cached)
	    {                   
	        return (_compliance!=LEGACY || orig.equals(cached) || complianceViolation(RFC2616,"case sensitive"))?cached:orig;
	    }
	    
	    /* ------------------------------------------------------------------------------- */
	    @Override
		public long getContentLength()
	    {
	        return _contentLength;
	    }

	    /* ------------------------------------------------------------ */
	    @Override
		public long getContentRead()
	    {
	        return _contentPosition;
	    }

	    /* ------------------------------------------------------------ */
	    /** Set if a HEAD response is expected
	     * @param head true if head response is expected
	     */
	    @Override
		public void setHeadResponse(final boolean head)
	    {
	        _headResponse=head;
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		protected void setResponseStatus(final int status)
	    {
	        _responseStatus=status;
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		public State getState()
	    {
	        return _state;
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		public boolean inContentState()
	    {
	        return _state.ordinal()>=State.CONTENT.ordinal() && _state.ordinal()<State.END.ordinal();
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		public boolean inHeaderState()
	    {
	        return _state.ordinal() < State.CONTENT.ordinal();
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		public boolean isChunking()
	    {
	        return _endOfContent==EndOfContent.CHUNKED_CONTENT;
	    }

	    /* ------------------------------------------------------------ */
	    @Override
		public boolean isStart()
	    {
	        return isState(State.START);
	    }

	    /* ------------------------------------------------------------ */
	    @Override
		public boolean isClose()
	    {
	        return isState(State.CLOSE);
	    }

	    /* ------------------------------------------------------------ */
	    @Override
		public boolean isClosed()
	    {
	        return isState(State.CLOSED);
	    }

	    /* ------------------------------------------------------------ */
	    @Override
		public boolean isIdle()
	    {
	        return __idleStates.contains(_state);
	    }

	    /* ------------------------------------------------------------ */
	    @Override
		public boolean isComplete()
	    {
	        return __completeStates.contains(_state);
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		public boolean isState(final State state)
	    {
	        return _state == state;
	    }

	    /* ------------------------------------------------------------------------------- */
	    enum CharState { ILLEGAL, CR, LF, LEGAL }
	    static
	    {
	        // token          = 1*tchar
	        // tchar          = "!" / "#" / "$" / "%" / "&" / "'" / "*"
	        //                / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
	        //                / DIGIT / ALPHA
	        //                ; any VCHAR, except delimiters
	        // quoted-string  = DQUOTE *( qdtext / quoted-pair ) DQUOTE
	        // qdtext         = HTAB / SP /%x21 / %x23-5B / %x5D-7E / obs-text
	        // obs-text       = %x80-FF
	        // comment        = "(" *( ctext / quoted-pair / comment ) ")"
	        // ctext          = HTAB / SP / %x21-27 / %x2A-5B / %x5D-7E / obs-text
	        // quoted-pair    = "\" ( HTAB / SP / VCHAR / obs-text )

	        __charState=new CharState[256];
	        Arrays.fill(__charState,CharState.ILLEGAL);
	        __charState[LINE_FEED]=CharState.LF;
	        __charState[CARRIAGE_RETURN]=CharState.CR;
	        __charState[TAB]=CharState.LEGAL;
	        __charState[SPACE]=CharState.LEGAL;

	        __charState['!']=CharState.LEGAL;
	        __charState['#']=CharState.LEGAL;
	        __charState['$']=CharState.LEGAL;
	        __charState['%']=CharState.LEGAL;
	        __charState['&']=CharState.LEGAL;
	        __charState['\'']=CharState.LEGAL;
	        __charState['*']=CharState.LEGAL;
	        __charState['+']=CharState.LEGAL;
	        __charState['-']=CharState.LEGAL;
	        __charState['.']=CharState.LEGAL;
	        __charState['^']=CharState.LEGAL;
	        __charState['_']=CharState.LEGAL;
	        __charState['`']=CharState.LEGAL;
	        __charState['|']=CharState.LEGAL;
	        __charState['~']=CharState.LEGAL;

	        __charState['"']=CharState.LEGAL;

	        __charState['\\']=CharState.LEGAL;
	        __charState['(']=CharState.LEGAL;
	        __charState[')']=CharState.LEGAL;
	        Arrays.fill(__charState,0x21,0x27+1,CharState.LEGAL);
	        Arrays.fill(__charState,0x2A,0x5B+1,CharState.LEGAL);
	        Arrays.fill(__charState,0x5D,0x7E+1,CharState.LEGAL);
	        Arrays.fill(__charState,0x80,0xFF+1,CharState.LEGAL);

	    }

	    /* ------------------------------------------------------------------------------- */
	    private byte next(final ByteBuffer buffer)
	    {
	        final byte ch = buffer.get();

	        final CharState s = __charState[0xff & ch];
	        switch(s)
	        {
	            case ILLEGAL:
	                throw new IllegalCharacterException(_state,ch,buffer);

	            case LF:
	                _cr=false;
	                break;

	            case CR:
	                if (_cr)
	                    throw new BadMessageException("Bad EOL");

	                _cr=true;
	                if (buffer.hasRemaining())
	                {
	                    if(_maxHeaderBytes>0 && _state.ordinal()<State.END.ordinal())
	                        _headerBytes++;
	                    return next(buffer);
	                }

	                // Can return 0 here to indicate the need for more characters,
	                // because a real 0 in the buffer would cause a BadMessage below
	                return 0;

	            case LEGAL:
	                if (_cr)
	                    throw new BadMessageException("Bad EOL");

	        }

	        return ch;
	    }

	    /* ------------------------------------------------------------------------------- */
	    /* Quick lookahead for the start state looking for a request method or a HTTP version,
	     * otherwise skip white space until something else to parse.
	     */
	    private boolean quickStart(final ByteBuffer buffer)
	    {
	        if (_requestHandler!=null)
	        {
	            _method = HttpMethod.lookAheadGet(buffer);
	            if (_method!=null)
	            {
	                _methodString = _method.asString();
	                buffer.position(buffer.position()+_methodString.length()+1);

	                setState(State.SPACE1);
	                return false;
	            }
	        }
	        else if (_responseHandler!=null)
	        {
	            _version = HttpVersion.lookAheadGet(buffer);
	            if (_version!=null)
	            {
	                buffer.position(buffer.position()+_version.asString().length()+1);
	                setState(State.SPACE1);
	                return false;
	            }
	        }

	        // Quick start look
	        while (_state==State.START && buffer.hasRemaining())
	        {
	            final int ch=next(buffer);

	            if (ch > SPACE)
	            {
	                _string.setLength(0);
	                _string.append((char)ch);
	                setState(_requestHandler!=null?State.METHOD:State.RESPONSE_VERSION);
	                return false;
	            }
	            else if (ch==0)
	                break;
	            else if (ch<0)
	                throw new BadMessageException();

	            // count this white space as a header byte to avoid DOS
	            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
	            {
	                LOG.warn("padding is too large >"+_maxHeaderBytes);
	                throw new BadMessageException(HttpStatus.BAD_REQUEST_400);
	            }
	        }
	        return false;
	    }

	    /* ------------------------------------------------------------------------------- */
	    private void setString(final String s)
	    {
	        _string.setLength(0);
	        _string.append(s);
	        _length=s.length();
	    }

	    /* ------------------------------------------------------------------------------- */
	    private String takeString()
	    {
	        _string.setLength(_length);
	        final String s =_string.toString();
	        _string.setLength(0);
	        _length=-1;
	        return s;
	    }

	    /* ------------------------------------------------------------------------------- */
	    /* Parse a request or response line
	     */
	    private boolean parseLine(final ByteBuffer buffer)
	    {
	        boolean handle=false;

	        // Process headers
	        while (_state.ordinal()<State.HEADER.ordinal() && buffer.hasRemaining() && !handle)
	        {
	            // process each character
	            final byte ch=next(buffer);
	            if (ch==0)
	                break;

	            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
	            {
	                if (_state==State.URI)
	                {
	                    LOG.warn("URI is too large >"+_maxHeaderBytes);
	                    throw new BadMessageException(HttpStatus.REQUEST_URI_TOO_LONG_414);
	                }
	                else
	                {
	                    if (_requestHandler!=null)
	                        LOG.warn("request is too large >"+_maxHeaderBytes);
	                    else
	                        LOG.warn("response is too large >"+_maxHeaderBytes);
	                    throw new BadMessageException(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413);
	                }
	            }

	            switch (_state)
	            {
	                case METHOD:
	                    if (ch == SPACE)
	                    {
	                        _length=_string.length();
	                        _methodString=takeString();
	                        final HttpMethod method=HttpMethod.CACHE.get(_methodString);
	                        if (method!=null)
	                            _methodString=legacyString(_methodString,method.asString());
	                        setState(State.SPACE1);
	                    }
	                    else if (ch < SPACE)
	                    {
	                        if (ch==LINE_FEED)
	                            throw new BadMessageException("No URI");
	                        else
	                            throw new IllegalCharacterException(_state,ch,buffer);
	                    }
	                    else
	                        _string.append((char)ch);
	                    break;

	                case RESPONSE_VERSION:
	                    if (ch == HttpTokens.SPACE)
	                    {
	                        _length=_string.length();
	                        final String version=takeString();
	                        _version=HttpVersion.CACHE.get(version);
	                        if (_version==null)
	                            throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"Unknown Version");
	                        setState(State.SPACE1);
	                    }
	                    else if (ch < HttpTokens.SPACE)
	                        throw new IllegalCharacterException(_state,ch,buffer);
	                    else
	                        _string.append((char)ch);
	                    break;

	                case SPACE1:
	                    if (ch > HttpTokens.SPACE || ch<0)
	                    {
	                        if (_responseHandler!=null)
	                        {
	                            setState(State.STATUS);
	                            setResponseStatus(ch-'0');
	                        }
	                        else
	                        {
	                            _uri.reset();
	                            setState(State.URI);
	                            // quick scan for space or EoBuffer
	                            if (buffer.hasArray())
	                            {
	                                final byte[] array=buffer.array();
	                                final int p=buffer.arrayOffset()+buffer.position();
	                                final int l=buffer.arrayOffset()+buffer.limit();
	                                int i=p;
	                                while (i<l && array[i]>HttpTokens.SPACE)
	                                    i++;

	                                final int len=i-p;
	                                _headerBytes+=len;

	                                if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
	                                {
	                                    LOG.warn("URI is too large >"+_maxHeaderBytes);
	                                    throw new BadMessageException(HttpStatus.REQUEST_URI_TOO_LONG_414);
	                                }
	                                _uri.append(array,p-1,len+1);
	                                buffer.position(i-buffer.arrayOffset());
	                            }
	                            else
	                                _uri.append(ch);
	                        }
	                    }
	                    else if (ch < HttpTokens.SPACE)
	                    {
	                        throw new BadMessageException(HttpStatus.BAD_REQUEST_400,_requestHandler!=null?"No URI":"No Status");
	                    }
	                    break;

	                case STATUS:
	                    if (ch == HttpTokens.SPACE)
	                    {
	                        setState(State.SPACE2);
	                    }
	                    else if (ch>='0' && ch<='9')
	                    {
	                        _responseStatus=_responseStatus*10+(ch-'0');
	                    }
	                    else if (ch < HttpTokens.SPACE && ch>=0)
	                    {
	                        setState(State.HEADER);
	                        handle=_responseHandler.startResponse(_version, _responseStatus, null)||handle;
	                    }
	                    else
	                    {
	                        throw new BadMessageException();
	                    }
	                    break;

	                case URI:
	                    if (ch == HttpTokens.SPACE)
	                    {
	                        setState(State.SPACE2);
	                    }
	                    else if (ch < HttpTokens.SPACE && ch>=0)
	                    {
	                        // HTTP/0.9
	                        if (complianceViolation(RFC7230,"HTTP/0.9"))
	                            throw new BadMessageException("HTTP/0.9 not supported");
	                        handle=_requestHandler.startRequest(_methodString,_uri.toString(), HttpVersion.HTTP_0_9);
	                        setState(State.END);
	                        BufferUtil.clear(buffer);
	                        handle=_handler.headerComplete()||handle;
	                        handle=_handler.messageComplete()||handle;
	                        return handle;
	                    }
	                    else
	                    {
	                        _uri.append(ch);
	                    }
	                    break;

	                case SPACE2:
	                    if (ch > HttpTokens.SPACE)
	                    {
	                        _string.setLength(0);
	                        _string.append((char)ch);
	                        if (_responseHandler!=null)
	                        {
	                            _length=1;
	                            setState(State.REASON);
	                        }
	                        else
	                        {
	                            setState(State.REQUEST_VERSION);

	                            // try quick look ahead for HTTP Version
	                            HttpVersion version;
	                            if (buffer.position()>0 && buffer.hasArray())
	                                version=HttpVersion.lookAheadGet(buffer.array(),buffer.arrayOffset()+buffer.position()-1,buffer.arrayOffset()+buffer.limit());
	                            else
	                                version=HttpVersion.CACHE.getBest(buffer,0,buffer.remaining());

	                            if (version!=null)
	                            {
	                                final int pos = buffer.position()+version.asString().length()-1;
	                                if (pos<buffer.limit())
	                                {
	                                    final byte n=buffer.get(pos);
	                                    if (n==HttpTokens.CARRIAGE_RETURN)
	                                    {
	                                        _cr=true;
	                                        _version=version;
	                                        _string.setLength(0);
	                                        buffer.position(pos+1);
	                                    }
	                                    else if (n==HttpTokens.LINE_FEED)
	                                    {
	                                        _version=version;
	                                        _string.setLength(0);
	                                        buffer.position(pos);
	                                    }
	                                }
	                            }
	                        }
	                    }
	                    else if (ch == HttpTokens.LINE_FEED)
	                    {
	                        if (_responseHandler!=null)
	                        {
	                            setState(State.HEADER);
	                            handle=_responseHandler.startResponse(_version, _responseStatus, null)||handle;
	                        }
	                        else
	                        {
	                            // HTTP/0.9
	                            if (complianceViolation(RFC7230,"HTTP/0.9"))
	                                throw new BadMessageException("HTTP/0.9 not supported");

	                            handle=_requestHandler.startRequest(_methodString,_uri.toString(), HttpVersion.HTTP_0_9);
	                            setState(State.END);
	                            BufferUtil.clear(buffer);
	                            handle=_handler.headerComplete()||handle;
	                            handle=_handler.messageComplete()||handle;
	                            return handle;
	                        }
	                    }
	                    else if (ch<0)
	                        throw new BadMessageException();
	                    break;

	                case REQUEST_VERSION:
	                    if (ch == HttpTokens.LINE_FEED)
	                    {
	                        if (_version==null)
	                        {
	                            _length=_string.length();
	                            _version=HttpVersion.CACHE.get(takeString());
	                        }
	                        if (_version==null)
	                            throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"Unknown Version");

	                        // Should we try to cache header fields?
	                        if (_connectionFields==null && _version.getVersion()>=HttpVersion.HTTP_1_1.getVersion() && _handler.getHeaderCacheSize()>0)
	                        {
	                            final int header_cache = _handler.getHeaderCacheSize();
	                            _connectionFields=new ArrayTernaryTrie<>(header_cache);
	                        }

	                        setState(State.HEADER);

	                        handle=_requestHandler.startRequest(_methodString,_uri.toString(), _version)||handle;
	                        continue;
	                    }
	                    else if (ch>=HttpTokens.SPACE)
	                        _string.append((char)ch);
	                    else
	                        throw new BadMessageException();

	                    break;

	                case REASON:
	                    if (ch == HttpTokens.LINE_FEED)
	                    {
	                        final String reason=takeString();
	                        setState(State.HEADER);
	                        handle=_responseHandler.startResponse(_version, _responseStatus, reason)||handle;
	                        continue;
	                    }
	                    else if (ch>=HttpTokens.SPACE)
	                    {
	                        _string.append((char)ch);
	                        if (ch!=' '&&ch!='\t')
	                            _length=_string.length();
	                    }
	                    else
	                        throw new BadMessageException();
	                    break;

	                default:
	                    throw new IllegalStateException(_state.toString());

	            }
	        }

	        return handle;
	    }

	    private void parsedHeader()
	    {
	        // handler last header if any.  Delayed to here just in case there was a continuation line (above)
	        if (_headerString!=null || _valueString!=null)
	        {
	            // Handle known headers
	            if (_header!=null)
	            {
	                boolean add_to_connection_trie=false;
	                switch (_header)
	                {
	                    case CONTENT_LENGTH:
	                        if (_endOfContent == EndOfContent.CONTENT_LENGTH)
	                        {
	                            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Duplicate Content-Length");
	                        }
	                        else if (_endOfContent != EndOfContent.CHUNKED_CONTENT)
	                        {
	                            _contentLength=convertContentLength(_valueString);
	                            if (_contentLength <= 0)
	                                _endOfContent=EndOfContent.NO_CONTENT;
	                            else
	                                _endOfContent=EndOfContent.CONTENT_LENGTH;
	                        }
	                        break;

	                    case TRANSFER_ENCODING:
	                        if (_value==HttpHeaderValue.CHUNKED)
	                        {
	                            _endOfContent=EndOfContent.CHUNKED_CONTENT;
	                            _contentLength=-1;
	                        }
	                        else
	                        {
	                            if (_valueString.endsWith(HttpHeaderValue.CHUNKED.toString()))
	                                _endOfContent=EndOfContent.CHUNKED_CONTENT;
	                            else if (_valueString.contains(HttpHeaderValue.CHUNKED.toString()))
	                                throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"Bad chunking");
	                        }
	                        break;

	                    case HOST:
	                        _host=true;
	                        if (!(_field instanceof HostPortHttpField))
	                        {
	                            _field=new HostPortHttpField(_header,legacyString(_headerString,_header.asString()),_valueString);
	                            add_to_connection_trie=_connectionFields!=null;
	                        }
	                      break;

	                    case CONNECTION:
	                        // Don't cache if not persistent
	                        if (_valueString!=null && _valueString.contains("close"))
	                            _connectionFields=null;

	                        break;

	                    case AUTHORIZATION:
	                    case ACCEPT:
	                    case ACCEPT_CHARSET:
	                    case ACCEPT_ENCODING:
	                    case ACCEPT_LANGUAGE:
	                    case COOKIE:
	                    case CACHE_CONTROL:
	                    case USER_AGENT:
	                        add_to_connection_trie=_connectionFields!=null && _field==null;
	                        break;

	                    default: break;
	                }

	                if (add_to_connection_trie && !_connectionFields.isFull() && _header!=null && _valueString!=null)
	                {
	                    if (_field==null)
	                        _field=new HttpField(_header,legacyString(_headerString,_header.asString()),_valueString);
	                    _connectionFields.put(_field);
	                }
	            }
	            _handler.parsedHeader(_field!=null?_field:new HttpField(_header,_headerString,_valueString));
	        }

	        _headerString=_valueString=null;
	        _header=null;
	        _value=null;
	        _field=null;
	    }

	    private long convertContentLength(final String valueString)
	    {
	        try
	        {
	            return Long.parseLong(valueString);
	        }
	        catch(final NumberFormatException e)
	        {
	            LOG.ignore(e);
	            throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"Invalid Content-Length Value");
	        }
	    }

	    /* ------------------------------------------------------------------------------- */
	    /*
	     * Parse the message headers and return true if the handler has signaled for a return
	     */
	    @Override
		protected boolean parseHeaders(final ByteBuffer buffer)
	    {
	        boolean handle=false;

	        // Process headers
	        while (_state.ordinal()<State.CONTENT.ordinal() && buffer.hasRemaining() && !handle)
	        {
	            // process each character
	            final byte ch=next(buffer);
	            if (ch==0)
	                break;

	            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
	            {
	                LOG.warn("Header is too large >"+_maxHeaderBytes);
	                throw new BadMessageException(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413);
	            }

	            switch (_state)
	            {
	                case HEADER:
	                    switch(ch)
	                    {
	                        case HttpTokens.COLON:
	                        case HttpTokens.SPACE:
	                        case HttpTokens.TAB:
	                        {
	                            if (complianceViolation(RFC7230,"header folding"))
	                                throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"Header Folding");

	                            // header value without name - continuation?
	                            if (_valueString==null)
	                            {
	                                _string.setLength(0);
	                                _length=0;
	                            }
	                            else
	                            {
	                                setString(_valueString);
	                                _string.append(' ');
	                                _length++;
	                                _valueString=null;
	                            }
	                            setState(State.HEADER_VALUE);
	                            break;
	                        }

	                        case HttpTokens.LINE_FEED:
	                        {
	                            // process previous header
	                            parsedHeader();

	                            _contentPosition=0;

	                            // End of headers!

	                            // Was there a required host header?
	                            if (!_host && _version==HttpVersion.HTTP_1_1 && _requestHandler!=null)
	                            {
//	                                throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"No Host");
	                            	BadMessageException e = new BadMessageException(HttpStatus.BAD_REQUEST_400,"No Host");
	                            	LOG.debug(e.getMessage(), e);
	                            }

	                            // is it a response that cannot have a body?
	                            if (_responseHandler !=null  && // response
	                                    (_responseStatus == 304  || // not-modified response
	                                    _responseStatus == 204 || // no-content response
	                                    _responseStatus < 200)) // 1xx response
	                                _endOfContent=EndOfContent.NO_CONTENT; // ignore any other headers set

	                            // else if we don't know framing
	                            else if (_endOfContent == EndOfContent.UNKNOWN_CONTENT)
	                            {
	                                if (_responseStatus == 0  // request
	                                        || _responseStatus == 304 // not-modified response
	                                        || _responseStatus == 204 // no-content response
	                                        || _responseStatus < 200) // 1xx response
	                                    _endOfContent=EndOfContent.NO_CONTENT;
	                                else
	                                    _endOfContent=EndOfContent.EOF_CONTENT;
	                            }

	                            // How is the message ended?
	                            switch (_endOfContent)
	                            {
	                                case EOF_CONTENT:
	                                    setState(State.EOF_CONTENT);
	                                    handle=_handler.headerComplete()||handle;
	                                    return handle;

	                                case CHUNKED_CONTENT:
	                                    setState(State.CHUNKED_CONTENT);
	                                    handle=_handler.headerComplete()||handle;
	                                    return handle;

	                                case NO_CONTENT:
	                                    setState(State.END);
	                                    handle=_handler.headerComplete()||handle;
	                                    handle=_handler.messageComplete()||handle;
	                                    return handle;

	                                default:
	                                    setState(State.CONTENT);
	                                    handle=_handler.headerComplete()||handle;
	                                    return handle;
	                            }
	                        }

	                        default:
	                        {
	                            // now handle the ch
	                            if (ch<HttpTokens.SPACE)
	                                throw new BadMessageException();

	                            // process previous header
	                            parsedHeader();

	                            // handle new header
	                            if (buffer.hasRemaining())
	                            {
	                                // Try a look ahead for the known header name and value.
	                                HttpField field=_connectionFields==null?null:_connectionFields.getBest(buffer,-1,buffer.remaining());
	                                if (field==null)
	                                    field=CACHE.getBest(buffer,-1,buffer.remaining());

	                                if (field!=null)
	                                {
	                                    final String n;
	                                    final String v;

	                                    if (_compliance==LEGACY)
	                                    {
	                                        // Have to get the fields exactly from the buffer to match case
	                                        final String fn=field.getName();
	                                        n=legacyString(BufferUtil.toString(buffer,buffer.position()-1,fn.length(),StandardCharsets.US_ASCII),fn);
	                                        final String fv=field.getValue();
	                                        if (fv==null)
	                                            v=null;
	                                        else
	                                        {
	                                            v=legacyString(BufferUtil.toString(buffer,buffer.position()+fn.length()+1,fv.length(),StandardCharsets.ISO_8859_1),fv);
	                                            field=new HttpField(field.getHeader(),n,v);
	                                        }
	                                    }
	                                    else
	                                    {
	                                        n=field.getName();
	                                        v=field.getValue();
	                                    }

	                                    _header=field.getHeader();
	                                    _headerString=n;

	                                    if (v==null)
	                                    {
	                                        // Header only
	                                        setState(State.HEADER_VALUE);
	                                        _string.setLength(0);
	                                        _length=0;
	                                        buffer.position(buffer.position()+n.length()+1);
	                                        break;
	                                    }
	                                    else
	                                    {
	                                        // Header and value
	                                        final int pos=buffer.position()+n.length()+v.length()+1;
	                                        final byte b=buffer.get(pos);

	                                        if (b==HttpTokens.CARRIAGE_RETURN || b==HttpTokens.LINE_FEED)
	                                        {
	                                            _field=field;
	                                            _valueString=v;
	                                            setState(State.HEADER_IN_VALUE);

	                                            if (b==HttpTokens.CARRIAGE_RETURN)
	                                            {
	                                                _cr=true;
	                                                buffer.position(pos+1);
	                                            }
	                                            else
	                                                buffer.position(pos);
	                                            break;
	                                        }
	                                        else
	                                        {
	                                            setState(State.HEADER_IN_VALUE);
	                                            setString(v);
	                                            buffer.position(pos);
	                                            break;
	                                        }
	                                    }
	                                }
	                            }

	                            // New header
	                            setState(State.HEADER_IN_NAME);
	                            _string.setLength(0);
	                            _string.append((char)ch);
	                            _length=1;

	                        }
	                    }
	                    break;

	                case HEADER_IN_NAME:
	                    if (ch==HttpTokens.COLON)
	                    {
	                        if (_headerString==null)
	                        {
	                            _headerString=takeString();
	                            _header=HttpHeader.CACHE.get(_headerString);
	                        }
	                        _length=-1;

	                        setState(State.HEADER_VALUE);
	                        break;
	                    }

	                    if (ch>HttpTokens.SPACE)
	                    {
	                        if (_header!=null)
	                        {
	                            setString(_header.asString());
	                            _header=null;
	                            _headerString=null;
	                        }

	                        _string.append((char)ch);
	                        if (ch>HttpTokens.SPACE)
	                            _length=_string.length();
	                        break;
	                    }
	                    
	                    if (ch==HttpTokens.LINE_FEED && !complianceViolation(RFC7230,"name only header"))
	                    {
	                        if (_headerString==null)
	                        {
	                            _headerString=takeString();
	                            _header=HttpHeader.CACHE.get(_headerString);
	                        }
	                        _value=null;
	                        _string.setLength(0);
	                        _valueString="";
	                        _length=-1;

	                        setState(State.HEADER);
	                        break;
	                    }

	                    throw new IllegalCharacterException(_state,ch,buffer);

	                case HEADER_VALUE:
	                    if (ch>HttpTokens.SPACE || ch<0)
	                    {
	                        _string.append((char)(0xff&ch));
	                        _length=_string.length();
	                        setState(State.HEADER_IN_VALUE);
	                        break;
	                    }

	                    if (ch==HttpTokens.SPACE || ch==HttpTokens.TAB)
	                        break;

	                    if (ch==HttpTokens.LINE_FEED)
	                    {
	                        _value=null;
	                        _string.setLength(0);
	                        _valueString="";
	                        _length=-1;

	                        setState(State.HEADER);
	                        break;
	                    }
	                    throw new IllegalCharacterException(_state,ch,buffer);

	                case HEADER_IN_VALUE:
	                    if (ch>=HttpTokens.SPACE || ch<0 || ch==HttpTokens.TAB)
	                    {
	                        if (_valueString!=null)
	                        {
	                            setString(_valueString);
	                            _valueString=null;
	                            _field=null;
	                        }
	                        _string.append((char)(0xff&ch));
	                        if (ch>HttpTokens.SPACE || ch<0)
	                            _length=_string.length();
	                        break;
	                    }

	                    if (ch==HttpTokens.LINE_FEED)
	                    {
	                        if (_length > 0)
	                        {
	                            _value=null;
	                            _valueString=takeString();
	                            _length=-1;
	                        }
	                        setState(State.HEADER);
	                        break;
	                    }

	                    throw new IllegalCharacterException(_state,ch,buffer);

	                default:
	                    throw new IllegalStateException(_state.toString());

	            }
	        }

	        return handle;
	    }

	    /* ------------------------------------------------------------------------------- */
	    /**
	     * Parse until next Event.
	     * @param buffer the buffer to parse
	     * @return True if an {@link RequestHandler} method was called and it returned true;
	     */
	    @Override
		public boolean parseNext(final ByteBuffer buffer)
	    {
	        if (DEBUG)
	            LOG.debug("parseNext s={} {}",_state,BufferUtil.toDetailString(buffer));
	        try
	        {
	            // Start a request/response
	            if (_state==State.START)
	            {
	                _version=null;
	                _method=null;
	                _methodString=null;
	                _endOfContent=EndOfContent.UNKNOWN_CONTENT;
	                _header=null;
	                if (quickStart(buffer))
	                    return true;
	            }

				// Request/response line
				if (_state.ordinal() >= State.START.ordinal() && _state.ordinal() < State.HEADER.ordinal() && parseLine(buffer))
				{
					return true;
				}

	            // parse headers
				if (_state.ordinal() >= State.HEADER.ordinal() && _state.ordinal() < State.CONTENT.ordinal() && parseHeaders(buffer))
				{
					return true;
				}

	            // parse content
	            if (_state.ordinal()>= State.CONTENT.ordinal() && _state.ordinal()<State.END.ordinal())
	            {
	                // Handle HEAD response
	                if (_responseStatus>0 && _headResponse)
	                {
	                    setState(State.END);
	                    return _handler.messageComplete();
	                }
	                else
	                {
	                    if (parseContent(buffer))
	                        return true;
	                }
	            }

	            // handle end states
	            if (_state==State.END)
	            {
	                // eat white space
	                while (buffer.remaining()>0 && buffer.get(buffer.position())<=HttpTokens.SPACE)
	                    buffer.get();
	            }
	            else if (_state==State.CLOSE)
	            {
	                // Seeking EOF
	                if (BufferUtil.hasContent(buffer))
	                {
	                    // Just ignore data when closed
	                    _headerBytes+=buffer.remaining();
	                    BufferUtil.clear(buffer);
	                    if (_maxHeaderBytes>0 && _headerBytes>_maxHeaderBytes)
	                    {
	                        // Don't want to waste time reading data of a closed request
	                        throw new IllegalStateException("too much data seeking EOF");
	                    }
	                }
	            }
	            else if (_state==State.CLOSED)
	            {
	                BufferUtil.clear(buffer);
	            }

	            // Handle EOF
	            if (_eof && !buffer.hasRemaining())
	            {
	                switch(_state)
	                {
	                    case CLOSED:
	                        break;

	                    case START:
	                        setState(State.CLOSED);
	                        _handler.earlyEOF();
	                        break;

	                    case END:
	                    case CLOSE:
	                        setState(State.CLOSED);
	                        break;

	                    case EOF_CONTENT:
	                        setState(State.CLOSED);
	                        return _handler.messageComplete();

	                    case  CONTENT:
	                    case  CHUNKED_CONTENT:
	                    case  CHUNK_SIZE:
	                    case  CHUNK_PARAMS:
	                    case  CHUNK:
	                        setState(State.CLOSED);
	                        _handler.earlyEOF();
	                        break;

	                    default:
	                        if (DEBUG)
	                            LOG.debug("{} EOF in {}",this,_state);
	                        setState(State.CLOSED);
	                        _handler.badMessage(400,null);
	                        break;
	                }
	            }
	        }
	        catch(final BadMessageException e)
	        {
	            BufferUtil.clear(buffer);

	            final Throwable cause = e.getCause();
	            final boolean stack = LOG.isDebugEnabled() ||
	                    (!(cause instanceof NumberFormatException )  && (cause instanceof RuntimeException || cause instanceof Error));

	            if (stack)
	                LOG.warn("bad HTTP parsed: "+e._code+(e.getReason()!=null?" "+e.getReason():"")+" for "+_handler,e);
	            else
	                LOG.warn("bad HTTP parsed: "+e._code+(e.getReason()!=null?" "+e.getReason():"")+" for "+_handler);
	            setState(State.CLOSE);
	            _handler.badMessage(e.getCode(), e.getReason());
	        }
	        catch(NumberFormatException|IllegalStateException e)
	        {
	            BufferUtil.clear(buffer);
	            LOG.warn("parse exception: {} in {} for {}",e.toString(),_state,_handler);
	            if (DEBUG)
	                LOG.debug(e);

	            switch(_state)
	            {
	                case CLOSED:
	                    break;
	                case CLOSE:
	                    _handler.earlyEOF();
	                    break;
	                default:
	                    setState(State.CLOSE);
	                    _handler.badMessage(400,null);
	            }
	        }
	        catch(Exception|Error e)
	        {
	            BufferUtil.clear(buffer);

	            LOG.warn("parse exception: "+e.toString()+" for "+_handler,e);

	            switch(_state)
	            {
	                case CLOSED:
	                    break;
	                case CLOSE:
	                    _handler.earlyEOF();
	                    break;
	                default:
	                    setState(State.CLOSE);
	                    _handler.badMessage(400,null);
	            }
	        }
	        return false;
	    }

	    @Override
		protected boolean parseContent(final ByteBuffer buffer)
	    {
	        int remaining=buffer.remaining();
	        if (remaining==0 && _state==State.CONTENT)
	        {
	            final long content=_contentLength - _contentPosition;
	            if (content == 0)
	            {
	                setState(State.END);
	                return _handler.messageComplete();
	            }
	        }

	        // Handle _content
	        byte ch;
	        while (_state.ordinal() < State.END.ordinal() && remaining>0)
	        {
	            switch (_state)
	            {
	                case EOF_CONTENT:
	                    _contentChunk=buffer.asReadOnlyBuffer();
	                    _contentPosition += remaining;
	                    buffer.position(buffer.position()+remaining);
	                    if (_handler.content(_contentChunk))
	                        return true;
	                    break;

	                case CONTENT:
	                {
	                    final long content=_contentLength - _contentPosition;
	                    if (content == 0)
	                    {
	                        setState(State.END);
	                        return _handler.messageComplete();
	                    }
	                    else
	                    {
	                        _contentChunk=buffer.asReadOnlyBuffer();

	                        // limit content by expected size
	                        if (remaining > content)
	                        {
	                            // We can cast remaining to an int as we know that it is smaller than
	                            // or equal to length which is already an int.
	                            _contentChunk.limit(_contentChunk.position()+(int)content);
	                        }

	                        _contentPosition += _contentChunk.remaining();
	                        buffer.position(buffer.position()+_contentChunk.remaining());

	                        if (_handler.content(_contentChunk))
	                            return true;

	                        if(_contentPosition == _contentLength)
	                        {
	                            setState(State.END);
	                            return _handler.messageComplete();
	                        }
	                    }
	                    break;
	                }

	                case CHUNKED_CONTENT:
	                {
	                    ch=next(buffer);
	                    if (ch>HttpTokens.SPACE)
	                    {
	                        _chunkLength=TypeUtil.convertHexDigit(ch);
	                        _chunkPosition=0;
	                        setState(State.CHUNK_SIZE);
	                    }

	                    break;
	                }

	                case CHUNK_SIZE:
	                {
	                    ch=next(buffer);
	                    if (ch==0)
	                        break;
	                    if (ch == HttpTokens.LINE_FEED)
	                    {
	                        if (_chunkLength == 0)
	                            setState(State.CHUNK_END);
	                        else
	                            setState(State.CHUNK);
	                    }
	                    else if (ch <= HttpTokens.SPACE || ch == HttpTokens.SEMI_COLON)
	                        setState(State.CHUNK_PARAMS);
	                    else
	                        _chunkLength=_chunkLength * 16 + TypeUtil.convertHexDigit(ch);
	                    break;
	                }

	                case CHUNK_PARAMS:
	                {
	                    ch=next(buffer);
	                    if (ch == HttpTokens.LINE_FEED)
	                    {
	                        if (_chunkLength == 0)
	                            setState(State.CHUNK_END);
	                        else
	                            setState(State.CHUNK);
	                    }
	                    break;
	                }

	                case CHUNK:
	                {
	                    int chunk=_chunkLength - _chunkPosition;
	                    if (chunk == 0)
	                    {
	                        setState(State.CHUNKED_CONTENT);
	                    }
	                    else
	                    {
	                        _contentChunk=buffer.asReadOnlyBuffer();

	                        if (remaining > chunk)
	                            _contentChunk.limit(_contentChunk.position()+chunk);
	                        chunk=_contentChunk.remaining();

	                        _contentPosition += chunk;
	                        _chunkPosition += chunk;
	                        buffer.position(buffer.position()+chunk);
	                        if (_handler.content(_contentChunk))
	                            return true;
	                    }
	                    break;
	                }

	                case CHUNK_END:
	                {
	                    // TODO handle chunk trailer
	                    ch=next(buffer);
	                    if (ch==0)
	                        break;
	                    if (ch == HttpTokens.LINE_FEED)
	                    {
	                        setState(State.END);
	                        return _handler.messageComplete();
	                    }
	                    throw new IllegalCharacterException(_state,ch,buffer);
	                }

	                case CLOSED:
	                {
	                    BufferUtil.clear(buffer);
	                    return false;
	                }

	                default:
	                    break;

	            }

	            remaining=buffer.remaining();
	        }
	        return false;
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		public boolean isAtEOF()

	    {
	        return _eof;
	    }

	    /* ------------------------------------------------------------------------------- */
	    /** Signal that the associated data source is at EOF
	     */
	    @Override
		public void atEOF()
	    {
	        if (DEBUG)
	            LOG.debug("atEOF {}", this);
	        _eof=true;
	    }

	    /* ------------------------------------------------------------------------------- */
	    /** Request that the associated data source be closed
	     */
	    @Override
		public void close()
	    {
	        if (DEBUG)
	            LOG.debug("close {}", this);
	        setState(State.CLOSE);
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		public void reset()
	    {
	        if (DEBUG)
	            LOG.debug("reset {}", this);

	        // reset state
	        if (_state==State.CLOSE || _state==State.CLOSED)
	            return;

	        setState(State.START);
	        _endOfContent=EndOfContent.UNKNOWN_CONTENT;
	        _contentLength=-1;
	        _contentPosition=0;
	        _responseStatus=0;
	        _contentChunk=null;
	        _headerBytes=0;
	        _host=false;
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		protected void setState(final State state)
	    {
	        if (DEBUG)
	            LOG.debug("{} --> {}",_state,state);
	        _state=state;
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
		public Trie<HttpField> getFieldCache()
	    {
	        return _connectionFields;
	    }

	    /* ------------------------------------------------------------------------------- */
	    private String getProxyField(final ByteBuffer buffer)
	    {
	        _string.setLength(0);
	        _length=0;

	        while (buffer.hasRemaining())
	        {
	            // process each character
	            final byte ch=next(buffer);
	            if (ch<=' ')
	                return _string.toString();
	            _string.append((char)ch);
	        }
	        throw new BadMessageException();
	    }

	    /* ------------------------------------------------------------------------------- */
	    @Override
	    public String toString()
	    {
	        return String.format("%s{s=%s,%d of %d}",
	                getClass().getSimpleName(),
	                _state,
	                _contentPosition,
	                _contentLength);
	    }

	    /* ------------------------------------------------------------------------------- */
	    @SuppressWarnings("serial")
	    private static class IllegalCharacterException extends BadMessageException
	    {
	        private IllegalCharacterException(final State state,final byte ch,final ByteBuffer buffer)
	        {
	            super(400,String.format("Illegal character 0x%X",ch));
	            // Bug #460642 - don't reveal buffers to end user
	            LOG.warn(String.format("Illegal character 0x%X in state=%s for buffer %s",ch,state,BufferUtil.toDetailString(buffer)));
	        }
	    }
}