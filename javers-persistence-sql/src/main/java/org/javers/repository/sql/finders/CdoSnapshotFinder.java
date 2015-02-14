package org.javers.repository.sql.finders;

import org.javers.common.collections.Optional;
import org.javers.core.commit.CommitId;
import org.javers.core.commit.CommitMetadata;
import org.javers.core.json.JsonConverter;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.core.metamodel.object.CdoSnapshotBuilder;
import org.javers.core.metamodel.object.GlobalId;
import org.javers.core.metamodel.object.SnapshotType;
import org.javers.core.metamodel.property.Property;
import org.javers.repository.sql.infrastructure.poly.JaversPolyJDBC;
import org.joda.time.LocalDateTime;
import org.polyjdbc.core.query.Order;
import org.polyjdbc.core.query.SelectQuery;
import org.polyjdbc.core.query.mapper.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.javers.repository.sql.domain.FixedSchemaFactory.*;

public class CdoSnapshotFinder {

    private final JaversPolyJDBC javersPolyJDBC;
    private final PropertiesFinder propertiesFinder;
    private JsonConverter jsonConverter;

    public CdoSnapshotFinder(JaversPolyJDBC javersPolyJDBC, PropertiesFinder propertiesFinder) {
        this.javersPolyJDBC = javersPolyJDBC;
        this.propertiesFinder = propertiesFinder;
    }

    public Optional<CdoSnapshot> getLatest(GlobalId globalId) {
        Optional<String> snapshotPk = selectSnapshotPrimaryKey(globalId);

        if (snapshotPk.isEmpty()) return Optional.empty();

        CommitDto commitDto = selectCommitMetadata(snapshotPk);
        List<SnapshotPropertyDTO> properties = propertiesFinder.findProperties(Integer.valueOf(snapshotPk.get()));
        
        CommitMetadata commitMetadata = new CommitMetadata(commitDto.author, commitDto.date, CommitId.valueOf(commitDto.commitId));
        CdoSnapshot snapshot = assemble(globalId, commitDto, properties, commitMetadata);

        return Optional.of( snapshot );
    }

    public List<CdoSnapshot> getStateHistory(GlobalId globalId, String className, int limit) {
        List<CommitDto> commits = selectCommit(globalId, className, limit);

        List<CdoSnapshot> snapshots = new ArrayList<>();

        //TODO n + 1 problem
        for (CommitDto commitDto : commits) {
            List<SnapshotPropertyDTO> properties = propertiesFinder.findProperties(commitDto.snapshotPk);
            CommitMetadata commitMetadata = new CommitMetadata(commitDto.author, commitDto.date, CommitId.valueOf(commitDto.commitId));

            snapshots.add( assemble(globalId, commitDto, properties, commitMetadata) );
        }

        return snapshots;
    }

    private List<CommitDto> selectCommit(GlobalId cdoId, String className, int limit) {
        SelectQuery query = javersPolyJDBC.query()
                .select(SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_PK + ", " +
                        SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_TYPE + ", " +
                        COMMIT_TABLE_NAME + "." + COMMIT_TABLE_AUTHOR + ", " +
                        COMMIT_TABLE_NAME + "." + COMMIT_TABLE_COMMIT_DATE + ", " + COMMIT_TABLE_COMMIT_ID)
                .from(SNAPSHOT_TABLE_NAME +
                        " INNER JOIN " + COMMIT_TABLE_NAME + " ON " + COMMIT_TABLE_NAME + "." + COMMIT_TABLE_PK + "=" + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_COMMIT_FK +
                        " INNER JOIN " + GLOBAL_ID_TABLE_NAME + " ON " + GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_PK + "=" + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_GLOBAL_ID_FK +
                        " INNER JOIN " + CDO_CLASS_TABLE_NAME + " ON " + CDO_CLASS_TABLE_NAME + "." + CDO_CLASS_PK + "=" + GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_CLASS_FK)
                .where(GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_LOCAL_ID + " = :localId AND " + CDO_CLASS_TABLE_NAME + "." + CDO_CLASS_QUALIFIED_NAME + " = :qualifiedName")
                .orderBy(SNAPSHOT_TABLE_PK, Order.DESC)
                .limit(limit)
                .withArgument("localId", jsonConverter.toJson(cdoId.getCdoId()))
                .withArgument("qualifiedName", className);

        return javersPolyJDBC.queryRunner().queryList(query, new ObjectMapper<CommitDto>() {
            @Override
            public CommitDto createObject(ResultSet resultSet) throws SQLException {
                return CommitDto.fromResultSet(resultSet);
            }
        });
    }

    private CdoSnapshot assemble(GlobalId globalId,
                                 CommitDto commitDto,
                                 List<SnapshotPropertyDTO> properties,
                                 CommitMetadata commitMetadata) {
        CdoSnapshotBuilder cdoSnapshotBuilder = CdoSnapshotBuilder.cdoSnapshot(globalId, commitMetadata);
        cdoSnapshotBuilder.withType(commitDto.snapshotType);

        for (SnapshotPropertyDTO propertyDTO : properties) {
            Property jProperty = globalId.getCdoClass().getProperty(propertyDTO.getName());

            Object propertyValue = jsonConverter.deserializePropertyValue(jProperty, propertyDTO.getValue());
            cdoSnapshotBuilder.withPropertyValue(jProperty, propertyValue);
        }

        return cdoSnapshotBuilder.build();
    }

    private CommitDto selectCommitMetadata(Optional<String> snapshotPk) {
        SelectQuery selectQuery2 = javersPolyJDBC.query()
                .select(SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_PK + ", " +
                        SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_TYPE + ", " +
                        COMMIT_TABLE_NAME + "." + COMMIT_TABLE_AUTHOR + ", " +
                        COMMIT_TABLE_NAME + "." + COMMIT_TABLE_COMMIT_DATE + ", " +
                        COMMIT_TABLE_NAME + "." + COMMIT_TABLE_COMMIT_ID)
                .from(SNAPSHOT_TABLE_NAME +
                      " INNER JOIN " + COMMIT_TABLE_NAME + " ON " +
                      COMMIT_TABLE_NAME + "." + COMMIT_TABLE_PK + "=" + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_COMMIT_FK)
                .where(SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_PK + " = :snapshotPk")
                .withArgument("snapshotPk", Integer.valueOf(snapshotPk.get()));

        List<CommitDto> commitDto = javersPolyJDBC.queryRunner().queryList(selectQuery2, new ObjectMapper<CommitDto>() {
            @Override
            public CommitDto createObject(ResultSet resultSet) throws SQLException {
                return CommitDto.fromResultSet(resultSet);
            }
        });

        return commitDto.get(0);
    }

    private Optional<String> selectSnapshotPrimaryKey(GlobalId globalId) {
        SelectQuery selectQuery = javersPolyJDBC.query()
                .select("MAX(" + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_PK + ") AS " + SNAPSHOT_TABLE_PK)
                .from(SNAPSHOT_TABLE_NAME +
                        " INNER JOIN " + GLOBAL_ID_TABLE_NAME + " ON " +
                        SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_GLOBAL_ID_FK + "=" + GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_PK +
                        " INNER JOIN " + CDO_CLASS_TABLE_NAME + " ON " +
                        GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_CLASS_FK + "=" + CDO_CLASS_TABLE_NAME + "." + CDO_CLASS_PK)
                .where(GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_LOCAL_ID +
                       " = :localId AND " + CDO_CLASS_TABLE_NAME + "." + CDO_CLASS_QUALIFIED_NAME + " = :qualifiedName")
                .withArgument("localId", jsonConverter.toJson(globalId.getCdoId()))
                .withArgument("qualifiedName", globalId.getCdoClass().getName());


        List<String> snapshotPk = javersPolyJDBC.queryRunner().queryList(selectQuery, new ObjectMapper<String>() {
            @Override
            public String createObject(ResultSet resultSet) throws SQLException {
                return resultSet.getString(SNAPSHOT_TABLE_PK);
            }
        });

        if (snapshotPk.size() != 1 || (snapshotPk.size() == 1 && snapshotPk.get(0) == null)) {
            return Optional.empty();
        }

        return Optional.of(snapshotPk.get(0));
    }

    //TODO dependency injection
    public void setJsonConverter(JsonConverter jsonConverter) {
        this.jsonConverter = jsonConverter;
    }

    private static class CommitDto {
        SnapshotType snapshotType;
        String author;
        LocalDateTime date;
        String commitId;
        int snapshotPk;

        CommitDto() {
        }

        static CommitDto fromResultSet(ResultSet resultSet) throws SQLException{
            CommitDto dto = new CommitDto();
            dto.snapshotPk = resultSet.getInt(SNAPSHOT_TABLE_PK);
            dto.snapshotType = SnapshotType.valueOf(resultSet.getString(SNAPSHOT_TABLE_TYPE));
            dto.author = resultSet.getString(COMMIT_TABLE_AUTHOR);
            dto.date =   new LocalDateTime(resultSet.getTimestamp(COMMIT_TABLE_COMMIT_DATE));
            dto.commitId = resultSet.getString(COMMIT_TABLE_COMMIT_ID);
            return dto;
        }

    }
}