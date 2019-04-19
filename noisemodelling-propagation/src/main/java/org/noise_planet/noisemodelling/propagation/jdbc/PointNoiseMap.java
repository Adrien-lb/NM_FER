package org.noise_planet.noisemodelling.propagation.jdbc;

import org.h2gis.api.ProgressVisitor;
import org.h2gis.utilities.JDBCUtilities;
import org.h2gis.utilities.SFSUtilities;
import org.h2gis.utilities.SpatialResultSet;
import org.h2gis.utilities.TableLocation;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.noise_planet.noisemodelling.propagation.ComputeRays;
import org.noise_planet.noisemodelling.propagation.ComputeRaysOut;
import org.noise_planet.noisemodelling.propagation.FastObstructionTest;
import org.noise_planet.noisemodelling.propagation.IComputeRaysOut;
import org.noise_planet.noisemodelling.propagation.LayerDelaunayError;
import org.noise_planet.noisemodelling.propagation.MeshBuilder;
import org.noise_planet.noisemodelling.propagation.PropagationProcessData;
import org.noise_planet.noisemodelling.propagation.PropagationProcessPathData;
import org.noise_planet.noisemodelling.propagation.PropagationResultPtRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Compute noise propagation at specified receiver points.
 * @author Nicolas Fortin
 */
public class PointNoiseMap extends JdbcNoiseMap {
    private final String receiverTableName;
    private PropagationProcessDataFactory propagationProcessDataFactory;
    private IComputeRaysOutFactory computeRaysOutFactory;
    private Logger logger = LoggerFactory.getLogger(PointNoiseMap.class);
    private PropagationProcessPathData propagationProcessPathData = new PropagationProcessPathData();

    public PointNoiseMap(String buildingsTableName, String sourcesTableName, String receiverTableName) {
        super(buildingsTableName, sourcesTableName);
        this.receiverTableName = receiverTableName;
    }

    public void setPropagationProcessPathData(PropagationProcessPathData propagationProcessPathData) {
        this.propagationProcessPathData = propagationProcessPathData;
    }

    public void setComputeRaysOutFactory(IComputeRaysOutFactory computeRaysOutFactory) {
        this.computeRaysOutFactory = computeRaysOutFactory;
    }

    /**
     * Initialisation of data structures needed for sound propagation.
     * @param connection JDBC Connection
     * @param cellI Cell I [0-{@link #getGridDim()}]
     * @param cellJ Cell J [0-{@link #getGridDim()}]
     * @param progression Progression info
     * @param receiversPk [out] receivers primary key extraction
     * @return Data input for cell evaluation
     * @throws SQLException
     */
    public PropagationProcessData prepareCell(Connection connection,int cellI, int cellJ,
                                              ProgressVisitor progression, Set<Long> skipReceivers) throws SQLException {
        MeshBuilder mesh = new MeshBuilder();
        int ij = cellI * gridDim + cellJ;
        logger.info("Begin processing of cell " + (cellI + 1) + ","
                + (cellJ + 1) + " of the " + gridDim + "x" + gridDim
                + "  grid..");
        Envelope cellEnvelope = getCellEnv(mainEnvelope, cellI,
                cellJ, getCellWidth(), getCellHeight());


        Envelope expandedCellEnvelop = new Envelope(cellEnvelope);
        expandedCellEnvelop.expandBy(maximumPropagationDistance);

        // //////////////////////////////////////////////////////
        // feed freeFieldFinder for fast intersection query
        // optimization
        // Fetch buildings in extendedEnvelope
        List<Integer> buildingsPK = new ArrayList<>();
        fetchCellBuildings(connection, expandedCellEnvelop, buildingsPK, mesh);
        //if we have topographic points data
        fetchCellDem(connection, expandedCellEnvelop, mesh);

        // Data fetching for collision test is done.
        try {
            mesh.finishPolygonFeeding(expandedCellEnvelop);
        } catch (LayerDelaunayError ex) {
            throw new SQLException(ex.getLocalizedMessage(), ex);
        }
        FastObstructionTest freeFieldFinder = new FastObstructionTest(mesh.getPolygonWithHeight(),
                mesh.getTriangles(), mesh.getTriNeighbors(), mesh.getVertices());


        PropagationProcessData propagationProcessData;
        if(propagationProcessDataFactory != null) {
            propagationProcessData = propagationProcessDataFactory.create(freeFieldFinder);
        } else {
            propagationProcessData = new PropagationProcessData(freeFieldFinder);
        }
        propagationProcessData.reflexionOrder = soundReflectionOrder;
        propagationProcessData.maxRefDist = maximumReflectionDistance;
        propagationProcessData.setComputeVerticalDiffraction(computeVerticalDiffraction);
        propagationProcessData.cellProg = progression.subProcess(propagationProcessData.receivers.size());

        // Fetch all source located in expandedCellEnvelop
        fetchCellSource(connection, expandedCellEnvelop, propagationProcessData);

        // Convert relative source coordinates to absolute ones
        propagationProcessData.makeRelativeZToAbsoluteOnlySources();
        propagationProcessData.cellId = ij;

        // Fetch soil areas
        fetchCellSoilAreas(connection, expandedCellEnvelop, propagationProcessData.getSoilList());

        // Fetch receivers

        String receiverGeomName = SFSUtilities.getGeometryFields(connection,
                TableLocation.parse(receiverTableName)).get(0);
        int intPk = JDBCUtilities.getIntegerPrimaryKey(connection, receiverTableName);
        String pkSelect = "";
        if(intPk >= 1) {
            pkSelect = ", " + JDBCUtilities.getFieldName(connection.getMetaData(), receiverTableName, intPk);
        } else {
            throw new SQLException(String.format("Table %s missing primary key for receiver identification", receiverTableName));
        }
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT " + TableLocation.quoteIdentifier(receiverGeomName) + pkSelect + " FROM " +
                        receiverTableName + " WHERE " +
                        TableLocation.quoteIdentifier(receiverGeomName) + " && ?::geometry")) {
            st.setObject(1, geometryFactory.toGeometry(cellEnvelope));
            try (SpatialResultSet rs = st.executeQuery().unwrap(SpatialResultSet.class)) {
                while (rs.next()) {
                    long receiverPk = rs.getLong(2);
                    if(skipReceivers.contains(receiverPk)) {
                        continue;
                    }
                    Geometry pt = rs.getGeometry();
                    if(pt != null && !pt.isEmpty()) {
                        propagationProcessData.addReceiver(receiverPk, pt.getCoordinate(), rs);
                    }
                }
            }
        }
        return propagationProcessData;
    }

    @Override
    protected Envelope getComputationEnvelope(Connection connection) throws SQLException {
        return SFSUtilities.getTableEnvelope(connection, TableLocation.parse(receiverTableName), "");
    }

    /**
     * Launch sound propagation
     * @param connection
     * @param cellI
     * @param cellJ
     * @param progression
     * @return
     * @throws SQLException
     */
    public IComputeRaysOut evaluateCell(Connection connection, int cellI, int cellJ,
                                        ProgressVisitor progression, Set<Long> skipReceivers) throws SQLException {
        PropagationProcessData threadData = prepareCell(connection, cellI, cellJ, progression, skipReceivers);

        IComputeRaysOut computeRaysOut;
        if(computeRaysOutFactory == null) {
            computeRaysOut = new ComputeRaysOut(false, propagationProcessPathData);
        } else {
            computeRaysOut = computeRaysOutFactory.create(threadData, propagationProcessPathData);
        }

        ComputeRays computeRays = new ComputeRays(threadData);

        if(!absoluteZCoordinates) {
            computeRays.makeRelativeZToAbsolute();
        }

        computeRays.run(computeRaysOut);

        return computeRaysOut;
    }

    public interface PropagationProcessDataFactory {
        PropagationProcessData create(FastObstructionTest freeFieldFinder);
    }

    public interface IComputeRaysOutFactory {
        IComputeRaysOut create(PropagationProcessData threadData, PropagationProcessPathData pathData);
    }
}
