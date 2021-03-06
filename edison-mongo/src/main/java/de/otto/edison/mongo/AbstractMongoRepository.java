package de.otto.edison.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.ReturnDocument.AFTER;
import static de.otto.edison.mongo.UpdateIfMatchResult.*;
import static java.util.Objects.isNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public abstract class AbstractMongoRepository<K, V> {

    protected static final String ID = "_id";
    protected static final String ETAG = "etag";

    private static final boolean DISABLE_PARALLEL_STREAM_PROCESSING = false;

    @PostConstruct
    public void postConstruct() {
        ensureIndexes();
    }

    public Optional<V> findOne(final K key) {
        return ofNullable(collection()
                .find(byId(key))
                .map(this::decode)
                .first());
    }

    /**
     * Convert given {@link Iterable} to a standard Java8-{@link Stream}.
     * The {@link Stream} requests elements from the iterable in a lazy fashion as they will usually,
     * so p.e. passing <code>collection().find()</code> as parameter will not result in the
     * whole collection being read into memory.
     * <p>
     * Parallel processing of the iterable is not used.
     *
     * @param iterable any {@link Iterable}
     * @param <T>      the type of elements returned by the iterator
     * @return a {@link Stream} wrapping the given {@link Iterable}
     */
    protected static <T> Stream<T> toStream(final Iterable<T> iterable) {
        return StreamSupport.stream(iterable.spliterator(), DISABLE_PARALLEL_STREAM_PROCESSING);
    }

    public Stream<V> findAllAsStream() {
        return toStream(collection().find())
                .map(this::decode);
    }

    /**
     * @deprecated Use {@link #findAllAsStream()} instead and collect the stream
     */
    @Deprecated
    public List<V> findAll() {
        return findAllAsStream().collect(toList());
    }

    public Stream<V> findAllAsStream(final int skip, final int limit) {
        return toStream(
                collection()
                        .find()
                        .skip(skip)
                        .limit(limit))
                .map(this::decode);
    }

    /**
     * @deprecated Use {@link #findAllAsStream(int, int)} instead and collect the stream
     */
    @Deprecated
    public List<V> findAll(final int skip, final int limit) {
        return findAllAsStream(skip, limit).collect(toList());
    }

    public V createOrUpdate(final V value) {
        final K key = keyOf(value);
        Document doc = encode(value);
        collection().replaceOne(byId(key), doc, new UpdateOptions().upsert(true));
        return decode(doc);
    }

    public V create(final V value) {
        Document doc = encode(value);
        collection().insertOne(doc);
        return decode(doc);
    }

    /**
     * Updates the document if it is already present in the repository.
     *
     * @param value the new value
     * @return true, if the document was updated, false otherwise.
     */
    public boolean update(final V value) {
        final K key = keyOf(value);
        return collection()
                .replaceOne(byId(key), encode(value))
                .getModifiedCount() == 1;
    }

    /**
     * Updates the document if the document's ETAG is matching the given etag (conditional put).
     * <p>
     * Using this method requires that the document contains an "etag" field that is updated if
     * the document is changed.
     * </p>
     *
     * @param value the new value
     * @param eTag  the etag used for conditional update
     * @return {@link UpdateIfMatchResult}
     */
    public UpdateIfMatchResult updateIfMatch(final V value, final String eTag) {
        final Bson query = and(eq(AbstractMongoRepository.ID, keyOf(value)), eq(ETAG, eTag));

        final Document updatedETaggable = collection().findOneAndReplace(query, encode(value), new FindOneAndReplaceOptions().returnDocument(AFTER));
        if (isNull(updatedETaggable)) {
            final boolean documentExists = collection().count(eq(AbstractMongoRepository.ID, keyOf(value))) != 0;
            if (documentExists) {
                return CONCURRENTLY_MODIFIED;
            }

            return NOT_FOUND;
        }

        return OK;
    }

    public long size() {
        return collection().count();
    }

    /**
     * Deletes the document identified by key.
     *
     * @param key the identifier of the deleted document
     * @return DeleteResult
     */
    public DeleteResult delete(final K key) {
        return collection().deleteOne(byId(key));
    }

    /**
     * Deletes all documents from this repository.
     *
     * @return DeleteResult
     */
    public DeleteResult deleteAll() {
        return collection().deleteMany(matchAll());
    }

    /**
     * Returns a query that is selecting documents by ID.
     *
     * @param key the document's key
     * @return query Document
     */
    protected Document byId(final K key) {
        return key != null ? new Document(ID, key.toString()) : new Document();
    }

    /**
     * Returns a query that is selecting all documents.
     *
     * @return query Document
     */
    protected Document matchAll() {
        return new Document();
    }

    /**
     * @return the MongoCollection used by this repository to store {@link Document documents}
     */
    protected abstract MongoCollection<Document> collection();

    /**
     * Returns the key / identifier from the given value.
     *
     * @param value the value
     * @return key
     */
    protected abstract K keyOf(final V value);

    /**
     * Encode a value into a MongoDB {@link Document}.
     *
     * @param value the value
     * @return Document
     */
    protected abstract Document encode(final V value);

    /**
     * Decode a MongoDB {@link Document} into a value.
     *
     * @param document the Document
     * @return V
     */
    protected abstract V decode(final Document document);

    /**
     * Ensure that the MongoDB indexes required by the repository do exist.
     * <p>
     * This method is called once after startup of the application.
     * </p>
     */
    protected abstract void ensureIndexes();
}
