package hex.svd;

import hex.DataInfo;
import hex.FrameTask;
import hex.Model;
import hex.ModelBuilder;
import hex.gram.Gram.GramTask;
import hex.schemas.ModelBuilderSchema;
import hex.schemas.SVDV3;
import water.*;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ArrayUtils;
import water.util.Log;
import water.util.TwoDimTable;

import java.util.Arrays;

/**
 * Singular Value Decomposition
 * <a href = "http://www.cs.yale.edu/homes/el327/datamining2013aFiles/07_singular_value_decomposition.pdf">SVD via Power Method Algorithm</a>
 * <a href = "https://www.cs.cmu.edu/~venkatg/teaching/CStheory-infoage/book-chapter-4.pdf">Proof of Convergence for Power Method</a>
 * @author anqi_fu
 */
public class SVD extends ModelBuilder<SVDModel,SVDModel.SVDParameters,SVDModel.SVDOutput> {
  // Convergence tolerance
  private final double TOLERANCE = 1e-6;    // Cutoff for estimation error of singular value \sigma_i

  // Number of columns in training set
  private transient int _ncols;

  @Override public ModelBuilderSchema schema() {
    return new SVDV3();
  }

  @Override public Job<SVDModel> trainModel() {
    return start(new SVDDriver(), 0);
  }

  @Override public Model.ModelCategory[] can_build() {
    return new Model.ModelCategory[]{ Model.ModelCategory.DimReduction };
  }

  // Called from an http request
  public SVD(SVDModel.SVDParameters parms) {
    super("SVD", parms);
    init(false);
  }

  @Override public void init(boolean expensive) {
    super.init(expensive);
    if (_parms._ukey == null) _parms._ukey = Key.make("SVDUMatrix_" + Key.rand());
    if (_parms._max_iterations < 1)
      error("_max_iterations", "max_iterations must be at least 1");

    if(_train == null) return;

    if(_parms._nv < 1 || _parms._nv > _train.numCols())
      error("_nv", "Number of right singular values must be between 1 and " + _train.numCols());

    Vec[] vecs = _train.vecs();
    for (int i = 0; i < vecs.length; i++) {
      if (!vecs[i].isNumeric()) {
        error("_train", "Training frame must contain all numeric data");
        break;
      }
    }
    _ncols = _train.numCols();
  }

  public double[] powerLoop(double[][] gram) {
    return powerLoop(gram, ArrayUtils.gaussianVector(gram[0].length));
  }
  public double[] powerLoop(double[][] gram, long seed) {
    return powerLoop(gram, ArrayUtils.gaussianVector(gram[0].length, seed));
  }
  public double[] powerLoop(double[][] gram, double[] vinit) {
    assert gram.length == gram[0].length;
    assert vinit.length == gram.length;

    // Set initial value v_0 to standard normal distribution
    int iters = 0;
    double err = 2 * TOLERANCE;
    double[] v = vinit.clone();
    double[] vnew = new double[v.length];

    // Update v_i <- (A'Av_{i-1})/||A'Av_{i-1}|| where A'A = Gram matrix of training frame
    while(iters < _parms._max_iterations && err > TOLERANCE) {
      // Compute x_i <- A'Av_{i-1} and ||x_i||
      for (int i = 0; i < v.length; i++)
        vnew[i] = ArrayUtils.innerProduct(gram[i], v);
      double norm = ArrayUtils.l2norm(vnew);

      double diff;
      for (int i = 0; i < v.length; i++) {
        vnew[i] /= norm;        // Compute singular vector v_i = x_i/||x_i||
        diff = v[i] - vnew[i];  // Save error ||v_i - v_{i-1}||
        err += diff * diff;
        v[i] = vnew[i];         // Update v_i for next iteration
      }
      err = Math.sqrt(err);
      iters++;
    }
    return v;
  }

  // Subtract two symmetric matrices
  public double[][] sub_symm(double[][] lmat, double[][] rmat) {
    for(int i = 0; i < rmat.length; i++) {
      for(int j = 0; j < i; j++) {
        double diff = lmat[i][j] - rmat[i][j];
        lmat[i][j] = lmat[j][i] = diff;
      }
      lmat[i][i] -= rmat[i][i];
    }
    return lmat;
  }

  class SVDDriver extends H2O.H2OCountedCompleter<SVDDriver> {

    protected void recoverPCA(SVDModel model) {
      // Eigenvectors are just the V matrix
      String[] colTypes = new String[_parms._nv];
      String[] colFormats = new String[_parms._nv];
      String[] colHeaders = new String[_parms._nv];
      Arrays.fill(colTypes, "double");
      Arrays.fill(colFormats, "%5f");
      for (int i = 0; i < colHeaders.length; i++) colHeaders[i] = "PC" + String.valueOf(i + 1);
      model._output._eigenvectors = new TwoDimTable("Rotation", null, _train.names(),
              colHeaders, colTypes, colFormats, "", new String[_train.numCols()][], model._output._v);

      // Compute standard deviation if D matrix was ouput
      if(!_parms._only_v) {
        double[] sdev = new double[model._output._d.length];
        double[] vars = new double[model._output._d.length];
        double totVar = 0;
        double dfcorr = 1.0 / Math.sqrt(_train.numRows() - 1.0);
        for (int i = 0; i < sdev.length; i++) {
          sdev[i] = dfcorr * model._output._d[i];
          vars[i] = sdev[i] * sdev[i];
          totVar += vars[i];
        }
        model._output._std_deviation = sdev;

        // Importance of principal components
        double[] prop_var = new double[vars.length];    // Proportion of total variance
        double[] cum_var = new double[vars.length];    // Cumulative proportion of total variance
        for(int i = 0; i < vars.length; i++) {
          prop_var[i] = vars[i]/totVar;
          cum_var[i] = i == 0 ? prop_var[0] : cum_var[i-1] + prop_var[i];
        }
        model._output._pc_importance = new TwoDimTable("Importance of components", null,
                new String[] { "Standard deviation", "Proportion of Variance", "Cumulative Proportion" },
                colHeaders, colTypes, colFormats, "", new String[3][], new double[][] { sdev, prop_var, cum_var });
      }
    }

    @Override protected void compute2() {
      SVDModel model = null;
      DataInfo uinfo = null, dinfo = null;
      Frame fr = null, u = null;

      try {
        _parms.read_lock_frames(SVD.this); // Fetch & read-lock input frames
        init(true);
        if (error_count() > 0) throw new IllegalArgumentException("Found validation errors: " + validationErrors());

        // The model to be built
        model = new SVDModel(dest(), _parms, new SVDModel.SVDOutput(SVD.this));
        model.delete_and_lock(_key);
        _train.read_lock(_key);

        // 0) Transform training data and save standardization vectors for use in scoring later
        dinfo = new DataInfo(Key.make(), _train, null, 0, false, _parms._transform, DataInfo.TransformType.NONE, true);
        DKV.put(dinfo._key, dinfo);

        model._output._normSub = dinfo._normSub == null ? new double[_train.numCols()] : Arrays.copyOf(dinfo._normSub, _train.numCols());
        if(dinfo._normMul == null) {
          model._output._normMul = new double[_train.numCols()];
          Arrays.fill(model._output._normMul, 1.0);
        } else
          model._output._normMul = Arrays.copyOf(dinfo._normMul, _train.numCols());

        // Calculate and save Gram matrix of training data
        // NOTE: Gram computes A'A/n where n = nrow(A) = number of rows in training set
        GramTask tsk = new GramTask(self(), dinfo).doAll(dinfo._adaptedFrame);
        double[][] gram = tsk._gram.getXX();    // TODO: This ends up with all NaNs if training data has too many missing values
        double[] sigma = new double[_parms._nv];
        double[][] rsvec = new double[_parms._nv][gram.length];

        // 1) Run one iteration of power method
        // 1a) Initialize right singular vector v_1
        rsvec[0] = powerLoop(gram, _parms._seed);

        // Keep track of I - \sum_i v_iv_i' where v_i = eigenvector i
        double[][] ivv_sum = new double[gram.length][gram.length];
        for(int i = 0; i < gram.length; i++) ivv_sum[i][i] = 1;

        // 1b) Initialize singular value \sigma_1 and update u_1 <- Av_1
        if(!_parms._only_v) {
          // Append vecs for storing left singular vectors (U) if requested
          Vec[] vecs = new Vec[_ncols + _parms._nv];
          Vec[] uvecs = new Vec[_parms._nv];
          for (int i = 0; i < _ncols; i++) vecs[i] = _train.vec(i);
          int c = 0;
          for (int i = _ncols; i < vecs.length; i++) {
            vecs[i] = _train.anyVec().makeZero();
            uvecs[c++] = vecs[i];   // Save reference to U only
          }
          assert c == uvecs.length;

          fr = new Frame(null, vecs);
          u = new Frame(_parms._ukey, null, uvecs);
          uinfo = new DataInfo(Key.make(), fr, null, 0, false, _parms._transform, DataInfo.TransformType.NONE, true);
          DKV.put(uinfo._key, uinfo);
          DKV.put(u._key, u);

          // Compute first singular value \sigma_1
          double[] ivv_vk = ArrayUtils.multArrVec(ivv_sum, rsvec[0]);
          sigma[0] = new CalcSigmaU(ivv_vk, _ncols, model._output._normSub, model._output._normMul).doAll(uinfo._adaptedFrame)._sval;
        }

        // 1c) Update Gram matrix A_1'A_1 = (I - v_1v_1')A'A(I - v_1v_1')
        double[][] vv = ArrayUtils.outerProduct(rsvec[0], rsvec[0]);
        ivv_sum = sub_symm(ivv_sum, vv);
        double[][] gram_update = ArrayUtils.multArrArr(ArrayUtils.multArrArr(ivv_sum, gram), ivv_sum);

        for(int k = 1; k < _parms._nv; k++) {
          // 2) Iterate x_i <- (A_k'A_k/n)x_{i-1} until convergence and set v_k = x_i/||x_i||
          rsvec[k] = powerLoop(gram_update, _parms._seed);

          // 3) Residual data A_k = A - \sum_{i=1}^k \sigma_i u_iv_i' = A - \sum_{i=1}^k Av_iv_i' = A(I - \sum_{i=1}^k v_iv_i')
          // 3a) Compute \sigma_k = ||A_{k-1}v_k|| and u_k = A_{k-1}v_k/\sigma_k
          if(!_parms._only_v) {
            double[] ivv_vk = ArrayUtils.multArrVec(ivv_sum, rsvec[k]);
            // sigma[k] = new CalcSigma(self(), dinfo, ivv_vk).doAll(dinfo._adaptedFrame)._sval;
            sigma[k] = new CalcSigmaUNorm(ivv_vk, k, sigma[k-1], _ncols, model._output._normSub, model._output._normMul).doAll(uinfo._adaptedFrame)._sval;
          }

          // 3b) Compute Gram of residual A_k'A_k = (I - \sum_{i=1}^k v_jv_j')A'A(I - \sum_{i=1}^k v_jv_j')
          // Update I - \sum_{i=1}^k v_iv_i' with sum up to current singular value
          vv = ArrayUtils.outerProduct(rsvec[k], rsvec[k]);
          ivv_sum = sub_symm(ivv_sum, vv);
          double[][] lmat = ArrayUtils.multArrArr(ivv_sum, gram);
          gram_update = ArrayUtils.multArrArr(lmat, ivv_sum);
        }

        // 4) Save solution to model output
        model._output._v = ArrayUtils.transpose(rsvec);
        if(!_parms._only_v) {
          // Normalize last left singular vector
          final double sigma_last = sigma[_parms._nv-1];
          new MRTask() {
            @Override public void map(Chunk cs[]) {
              div(chk_u(cs,_parms._nv-1, _ncols), sigma_last);
            }
          }.doAll(uinfo._adaptedFrame);
          model._output._d = sigma;
          model._output._ukey = _parms._ukey;
        }
        if(_parms._recover_pca) recoverPCA(model);
        done();
      } catch( Throwable t ) {
        Job thisJob = DKV.getGet(_key);
        if (thisJob._state == JobState.CANCELLED) {
          Log.info("Job cancelled by user.");
        } else {
          t.printStackTrace();
          failed(t);
          throw t;
        }
      } finally {
        if( model != null ) model.unlock(_key);
        if( dinfo != null ) dinfo.remove();
        if( uinfo != null ) uinfo.remove();
        _parms.read_unlock_frames(SVD.this);
      }
      tryComplete();
    }

    Key self() {
      return _key;
    }
  }

  // In chunk, first cols are training frame A, next cols are left singular vectors U
  // protected static int idx_u(int c) { return _ncols+c; }
  protected static Chunk chk_u(Chunk chks[], int c, int ncols) { return chks[ncols+c]; }

  // Save inner product of each row with vec to col k of chunk array
  // Returns sum over l2 norms of each row with vec
  private static double l2norm2(Chunk[] cs, double[] vec, int k, int ncols, double[] normSub, double[] normMul) {
    double sumsqr = 0;
    for (int row = 0; row < cs[0]._len; row++) {
      // Calculate inner product of current row with vec
      double sum = 0;
      for (int j = 0; j < ncols; j++) {
        double a = cs[j].atd(row);
        sum += (a - normSub[j]) * normMul[j] * vec[j];
      }
      sumsqr += sum * sum;
      chk_u(cs,k,ncols).set(row,sum);   // Update u_k <- A_{k-1}v_k
    }
    return sumsqr;
  }

  // Divide each row of a chunk by a constant
  private static void div(Chunk chk, double norm) {
    for(int row = 0; row < chk._len; row++) {
      double tmp = chk.atd(row);
      chk.set(row, tmp / norm);
    }
  }

  private static class CalcSigmaU extends MRTask<CalcSigmaU> {
    final int _ncols;
    final double[] _normSub;
    final double[] _normMul;
    final double[] _svec;   // Input: Right singular vector (v_1)
    double _sval;     // Output: Singular value (\sigma_1)

    CalcSigmaU(double[] svec, int ncols, double[] normSub, double[] normMul) {
      _svec = svec;
      _ncols = ncols;
      _normSub = normSub;
      _normMul = normMul;
      _sval = 0;
    }

    @Override public void map(Chunk[] cs) {
      assert cs.length - _ncols == _svec.length;
      _sval += l2norm2(cs, _svec, 0, _ncols, _normSub, _normMul);   // Update \sigma_1 and u_1 <- Av_1
    }

    @Override protected void postGlobal() {
      _sval = Math.sqrt(_sval);
    }
  }

  private static class CalcSigmaUNorm extends MRTask<CalcSigmaUNorm> {
    final int _k;             // Input: Index of current singular vector (k)
    final double[] _svec;     // Input: Right singular vector (v_k)
    final double _sval_old;  // Input: Singular value from last iteration (\sigma_{k-1})
    final int _ncols;
    final double[] _normSub;
    final double[] _normMul;

    double _sval;     // Output: Singular value (\sigma_k)

    CalcSigmaUNorm(double[] svec, int k, double sval_old, int ncols, double[] normSub, double[] normMul) {
      assert k >= 1;
      _k = k;
      _svec = svec;
      _ncols = ncols;
      _normSub = normSub;
      _normMul = normMul;
      _sval_old = sval_old;
      _sval = 0;
    }

    @Override public void map(Chunk[] cs) {
      assert cs.length - _ncols == _svec.length;
      _sval += l2norm2(cs, _svec, _k, _ncols, _normSub, _normMul);    // Update \sigma_k and save u_k <- A_{k-1}v_k
      div(chk_u(cs,_k-1, _ncols), _sval_old);     // Normalize previous u_{k-1} <- u_{k-1}/\sigma_{k-1}
    }

    @Override protected void postGlobal() {
      _sval = Math.sqrt(_sval);
    }
  }
}
