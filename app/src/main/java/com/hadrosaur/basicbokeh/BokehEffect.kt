package com.hadrosaur.basicbokeh

import android.os.Build
import com.hadrosaur.basicbokeh.MainActivity.Companion.BLUR_SCALE_FACTOR
import com.hadrosaur.basicbokeh.MainActivity.Companion.Logd
import kotlinx.android.synthetic.main.activity_main.*
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d.CALIB_ZERO_DISPARITY
import org.opencv.calib3d.StereoSGBM
import org.opencv.core.CvType.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d.stereoRectify
import org.opencv.calib3d.StereoBM
import java.nio.ByteBuffer
import org.opencv.calib3d.StereoSGBM.MODE_HH4
import org.opencv.core.*
import org.opencv.core.Core.*
import org.opencv.imgproc.Imgproc.*
import org.opencv.ximgproc.Ximgproc.createDisparityWLSFilter
import android.graphics.*
import org.opencv.calib3d.StereoMatcher
import org.opencv.core.Rect
import org.opencv.core.Mat
import org.opencv.ximgproc.DisparityWLSFilter
import org.opencv.core.Core.getTickFrequency
import org.opencv.core.Core.getTickCount
import org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY
import org.opencv.ximgproc.Ximgproc.createRightMatcher




fun DoBokeh(activity: MainActivity, twoLens: TwoLensCoordinator) : Bitmap {
    //We need both shots to be done and both images in order to proceed
    if (!twoLens.normalShotDone || !twoLens.wideShotDone || (null == twoLens.normalImage)
        || (null == twoLens.wideImage))
        return Bitmap.createBitmap(100, 100,  Bitmap.Config.ARGB_8888) //Return empty bitmap

    Logd("Normal image timestamp: " + twoLens.normalImage?.timestamp)
    Logd("Wide image timestamp: " + twoLens.wideImage?.timestamp)

    val wideBuffer: ByteBuffer? = twoLens.wideImage!!.planes[0].buffer
    val wideBytes = ByteArray(wideBuffer!!.remaining())
    wideBuffer.get(wideBytes)

    val normalBuffer: ByteBuffer? = twoLens.normalImage!!.planes[0].buffer
    val normalBytes = ByteArray(normalBuffer!!.remaining())
    normalBuffer.get(normalBytes)

    if (PrefHelper.getCalibrationMode(activity)) {
        //Calibration images. Save plain photos with timestamp
        WriteFile(activity, normalBytes, "NormalCalibration", true)
        WriteFile(activity, wideBytes, "WideCalibration", true)
    }

    val wideMat: Mat = Mat(twoLens.wideImage!!.height, twoLens.wideImage!!.width, CV_8UC1)
    val tempWideBitmap = BitmapFactory.decodeByteArray(wideBytes, 0, wideBytes.size, null)
    Utils.bitmapToMat(tempWideBitmap, wideMat)

    val normalMat: Mat = Mat(twoLens.normalImage!!.height, twoLens.normalImage!!.width, CV_8UC1)
    val tempNormalBitmap = BitmapFactory.decodeByteArray(normalBytes, 0, normalBytes.size, null)
    Utils.bitmapToMat(tempNormalBitmap, normalMat)

    if (PrefHelper.getIntermediate(activity)) {
        activity.runOnUiThread {
            activity.imageIntermediate1.setImageBitmap(horizontalFlip(rotateBitmap(tempNormalBitmap, -90f)))
            activity.imageIntermediate2.setImageBitmap(horizontalFlip(rotateBitmap(tempWideBitmap, -90f)))
        }
    }

    if (PrefHelper.getCalibrationMode(activity)) {
        return tempNormalBitmap
    }

    if (PrefHelper.getSaveIntermediate(activity)) {
        WriteFile(activity, tempWideBitmap,"WideShot")
        WriteFile(activity, tempNormalBitmap, "NormalShot")
    }

    //Convert the Mats to 1-channel greyscale so we can compute depth maps
    var finalNormalMat: Mat = Mat(normalMat.rows(), normalMat.cols(), CV_8UC1)
    Imgproc.cvtColor(normalMat, finalNormalMat, Imgproc.COLOR_BGR2GRAY)

    var finalWideMat: Mat = Mat(wideMat.rows(), wideMat.cols(), CV_8UC1)
    Imgproc.cvtColor(wideMat, finalWideMat, Imgproc.COLOR_BGR2GRAY)

    //Get camera matricies
    //If we are >= 28, rectify images to get a good depth map.
    if (Build.VERSION.SDK_INT >= 28) {

        val camMatrixNormal: Mat = Mat(3, 3, CV_64FC1)
        setMat(camMatrixNormal, 3, 3, cameraMatrixFromCalibration(twoLens.normalParams.intrinsicCalibration))
        val camMatrixWide: Mat = Mat(3, 3, CV_64FC1)
        setMat(camMatrixWide, 3, 3, cameraMatrixFromCalibration(twoLens.wideParams.intrinsicCalibration))

        var distCoeffNormal: Mat = Mat(5, 1, CV_64FC1)
        setMat(distCoeffNormal, 5, 1, cameraDistortionFromCalibration(twoLens.normalParams.lensDistortion))
        var distCoeffWide: Mat = Mat(5, 1, CV_64FC1)
        setMat(distCoeffWide, 5, 1, cameraDistortionFromCalibration(twoLens.wideParams.lensDistortion))

        /*
        Logd("Cam MAtrix K1 Check: "
                + camMatrixNormal[0, 0].get(0) + ", "
                + camMatrixNormal[0, 1].get(0) + ", "
                + camMatrixNormal[0, 2].get(0) + ", "
                + camMatrixNormal[1, 0].get(0) + ", "
                + camMatrixNormal[1, 1].get(0) + ", "
                + camMatrixNormal[1, 2].get(0) + ", "
                + camMatrixNormal[2, 0].get(0) + ", "
                + camMatrixNormal[2, 1].get(0) + ", "
                + camMatrixNormal[2, 2].get(0)
        )

        Logd("Cam MAtrix K2 Check: "
                + camMatrixWide[0, 0].get(0) + ", "
                + camMatrixWide[0, 1].get(0) + ", "
                + camMatrixWide[0, 2].get(0) + ", "
                + camMatrixWide[1, 0].get(0) + ", "
                + camMatrixWide[1, 1].get(0) + ", "
                + camMatrixWide[1, 2].get(0) + ", "
                + camMatrixWide[2, 0].get(0) + ", "
                + camMatrixWide[2, 1].get(0) + ", "
                + camMatrixWide[2, 2].get(0)
        )
*/
        /*
        distort = np.array(props_physical[i]['android.lens.distortion'])
            assert len(distort) == 5, 'distortion has wrong # of params.'
            cv2_distort = np.array([distort[0], distort[1],
                                    distort[3], distort[4],
                                    distort[2]])
         */


        var poseRotationNormal: Mat = Mat(3, 3, CV_64FC1)
        setMat(poseRotationNormal, 3, 3, rotationMatrixFromQuaternion(twoLens.normalParams.poseRotation))
        var poseRotationWide: Mat = Mat(3, 3, CV_64FC1)
        setMat(poseRotationWide, 3, 3, rotationMatrixFromQuaternion(twoLens.wideParams.poseRotation))

        var poseTranslationNormal: Mat = Mat(3, 1, CV_64FC1)
        setMat(poseTranslationNormal, 3, 1, floatArraytoDoubleArray(twoLens.normalParams.poseTranslation))
        var poseTranslationWide: Mat = Mat(3, 1, CV_64FC1)
        setMat(poseTranslationWide, 3, 1, floatArraytoDoubleArray(twoLens.wideParams.poseTranslation))

        var combinedR: Mat = Mat(3, 3, CV_64FC1)
        var combinedT: Mat = Mat(3, 1, CV_64FC1)

        val combinedT1: Mat = Mat()
        val combinedT2: Mat = Mat()

//        multiply(poseTranslationNormal, poseRotationNormal, combinedT, -1.0)
//        multiply(poseRotationNormal, poseTranslationNormal, combinedT)
//        multiply(poseRotationWide, poseTranslationWide, combinedT2, -1.0)
//        t[i] = -1.0 * np.dot(r[i], t[i])

        //To get T1 -> T2 we need to translate using -1 * innerproduct(R1 * T1) for each row. So:
        // T[0] = -1 * innerProduct(row0(R1) * T1)
        // T[1] = -1 * innerProduct(row1(R1) * T1)
        // T[2] = -1 * innerProduct(row2(R1) * T1)
        combinedT.put(0,0, -1.0 * poseRotationNormal.colRange(0, 1).dot(poseTranslationNormal))
        combinedT.put(1,0, -1.0 * poseRotationNormal.colRange(1, 2).dot(poseTranslationNormal))
//        combinedT.put(2,0, -1.0 * poseRotationNormal.colRange(2, 3).dot(poseTranslationNormal))
        combinedT.put(2,0, 1.0 * poseRotationNormal.colRange(2, 3).dot(poseTranslationNormal))

        //To get our combined R, inverse poseRotationWide and multiply
        Core.gemm(poseRotationWide.inv(DECOMP_SVD), poseRotationNormal, 1.0, Mat(), 0.0, combinedR)
//        Core.gemm(poseRotationNormal.inv(DECOMP_SVD), poseRotationWide, 1.0, Mat(), 0.0, combinedR)
//        combinedR = poseRotationNormal


        // NOTE todo For future if implementing for back cams
        //    if props['android.lens.facing']:
        //        print 'lens facing BACK'
        //        chart_distance *= -1  # API spec defines +z i pointing out from screen


        Logd("Final Combined Rotation Matrix: "
                + combinedR[0, 0].get(0) + ", "
                + combinedR[0, 1].get(0) + ", "
                + combinedR[0, 2].get(0) + ", "
                + combinedR[1, 0].get(0) + ", "
                + combinedR[1, 1].get(0) + ", "
                + combinedR[1, 2].get(0) + ", "
                + combinedR[2, 0].get(0) + ", "
                + combinedR[2, 1].get(0) + ", "
                + combinedR[2, 2].get(0)
        )

        Logd("Final Combined Translation Matrix: "
                + combinedT[0, 0].get(0) + ", "
                + combinedT[1, 0].get(0) + ", "
                + combinedT[2, 0].get(0)
        )

        //Stereo rectify
        var R1: Mat = Mat(3, 3, CV_64FC1)
        var R2: Mat = Mat(3, 3, CV_64FC1)
        var P1: Mat = Mat(3, 4, CV_64FC1)
        var P2: Mat = Mat(3, 4, CV_64FC1)
        var Q: Mat = Mat(4, 4, CV_64FC1)

        val roi1: Rect = Rect()
        val roi2: Rect = Rect()

        stereoRectify(camMatrixNormal, distCoeffNormal, camMatrixWide, distCoeffWide,
                finalNormalMat.size(), combinedR, combinedT, R1, R2, P1, P2, Q,
                CALIB_ZERO_DISPARITY, 0.0, Size(), roi1, roi2)

        Logd("R1: " + R1[0,0].get(0) + ", " + R1[0,1].get(0) + ", " + R1[0,2].get(0) + ", " + R1[1,0].get(0) + ", " + R1[1,1].get(0) + ", " + R1[1,2].get(0) + ", " + R1[2,0].get(0) + ", " + R1[2,1].get(0) + ", " + R1[2,2].get(0))
        Logd("R2: " + R2[0,0].get(0) + ", " + R2[0,1].get(0) + ", " + R2[0,2].get(0) + ", " + R2[1,0].get(0) + ", " + R2[1,1].get(0) + ", " + R2[1,2].get(0) + ", " + R2[2,0].get(0) + ", " + R2[2,1].get(0) + ", " + R2[2,2].get(0))
        Logd("P1: " + P1[0,0].get(0) + ", " + P1[0,1].get(0) + ", " + P1[0,2].get(0) + ", " + P1[0,3].get(0) + ", " + P1[1,0].get(0) + ", " + P1[1,1].get(0) + ", " + P1[1,2].get(0) + ", " + P1[1,3].get(0) + ", "
                + P1[2,0].get(0) + ", " + P1[2,1].get(0) + ", " + P1[2,2].get(0) + ", " + P1[2,3].get(0))
        Logd("P2: " + P2[0,0].get(0) + ", " + P2[0,1].get(0) + ", " + P2[0,2].get(0) + ", " + P2[0,3].get(0) + ", " + P2[1,0].get(0) + ", " + P2[1,1].get(0) + ", " + P2[1,2].get(0) + ", " + P2[1,3].get(0) + ", "
                + P2[2,0].get(0) + ", " + P2[2,1].get(0) + ", " + P2[2,2].get(0) + ", " + P2[2,3].get(0))

        val mapNormal1: Mat = Mat()
        val mapNormal2: Mat = Mat()
        val mapWide1: Mat = Mat()
        val mapWide2: Mat = Mat()

        //100+mb allocation each
        initUndistortRectifyMap(camMatrixNormal, distCoeffNormal, R1, P1, finalNormalMat.size(), CV_32F, mapNormal1, mapNormal2);
        initUndistortRectifyMap(camMatrixWide, distCoeffWide, R2, P2, finalWideMat.size(), CV_32F, mapWide1, mapWide2);

        var rectifiedNormalMat: Mat = Mat()
        var rectifiedWideMat: Mat = Mat()


        remap(finalNormalMat, rectifiedNormalMat, mapNormal1, mapNormal2, INTER_LINEAR);
        remap(finalWideMat, rectifiedWideMat, mapWide1, mapWide2, INTER_LINEAR);

        mapNormal1.release()
        mapNormal2.release()
        mapWide1.release()
        mapWide2.release()

        //Individually rectify
//        undistort(finalNormalMat, rectifiedNormalMat, camMatrixNormal, distCoeffNormal)
//        undistort(finalWideMat, rectifiedWideMat, camMatrixWide, distCoeffWide)

        //Crop the wide angle shot so that has the same frame of view as the normal shot
//    val scaleFactor: Float = twoLens.wideParams.smallestFocalLength / twoLens.normalParams.smallestFocalLength
//        val scaleFactor = 0.813f
//        val scaleFactor = 0.84f
//        rectifiedWideMat = cropMat(rectifiedWideMat, scaleFactor)
//        Imgproc.resize(rectifiedNormalMat, rectifiedNormalMat, rectifiedWideMat.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)


        Logd( "Now saving rectified photos to disk.")
        val rectifiedNormalBitmap: Bitmap = Bitmap.createBitmap(rectifiedNormalMat.cols(), rectifiedNormalMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rectifiedNormalMat, rectifiedNormalBitmap)
        val rectifiedWideBitmap: Bitmap = Bitmap.createBitmap(rectifiedWideMat.cols(), rectifiedWideMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rectifiedWideMat, rectifiedWideBitmap)

        if (PrefHelper.getSaveIntermediate(activity)) {
            WriteFile(activity, rectifiedNormalBitmap, "RectifiedNormalShot")
            WriteFile(activity, rectifiedWideBitmap,"RectifiedWideShot")
        }

        if (PrefHelper.getIntermediate(activity)) {
            activity.runOnUiThread {
                activity.imageIntermediate3.setImageBitmap(rotateBitmap(rectifiedNormalBitmap,-90f))
                activity.imageIntermediate4.setImageBitmap(rotateBitmap(rectifiedWideBitmap, -90f))
            }
        }

        finalNormalMat.release()
        finalWideMat.release()

        finalNormalMat = rectifiedNormalMat
        finalWideMat = rectifiedWideMat
    }

    val sgbmWinSize = PrefHelper.getWindowSize(activity)
    val sgbmBlockSize = sgbmWinSize
    val sgbmMinDisparity = 0
    val sgbmNumDisparities = PrefHelper.getNumDisparities(activity)
    val sgbmP1 = PrefHelper.getP1(activity)
    val sgbmP2 = PrefHelper.getP1(activity)
    val sgbmDispMaxDiff = -1
    val sgbmPreFilterCap = PrefHelper.getPrefilter(activity)
    val sgbmUniquenessRatio = 0
    val sgbmSpeckleSize = PrefHelper.getSpecklesize(activity)
    val sgbmSpeckleRange = PrefHelper.getSpecklerange(activity)
    val sgbmMode = StereoSGBM.MODE_HH4
//    val sgbmMode = StereoSGBM.MODE_SGBM


    val resizedNormalMat: Mat = Mat()
    val resizedWideMat: Mat = Mat()

    val depthMapScaleFactor = 0.25f

    //Scale down so we have a chance of not burning through the heap
    resize(finalNormalMat, resizedNormalMat, Size((finalNormalMat.width() * depthMapScaleFactor).toDouble(), (finalNormalMat.height() * depthMapScaleFactor).toDouble()))
    resize(finalWideMat, resizedWideMat, Size((finalWideMat.width() / 4).toDouble(), (finalWideMat.height() / 4).toDouble()))

    val rotatedNormalMat: Mat = Mat()
    val rotatedWideMat: Mat = Mat()

    rotate(resizedNormalMat, rotatedNormalMat, Core.ROTATE_90_CLOCKWISE)
    rotate(resizedWideMat, rotatedWideMat, Core.ROTATE_90_CLOCKWISE)

    resizedNormalMat.release()
    resizedWideMat.release()

    val disparityMat: Mat = Mat(rotatedNormalMat.rows(), rotatedNormalMat.cols(), CV_8UC1)
    val disparityMat2: Mat = Mat(rotatedNormalMat.rows(), rotatedNormalMat.cols(), CV_8UC1)

    val stereoBM: StereoSGBM = StereoSGBM.create(sgbmMinDisparity, sgbmNumDisparities, sgbmBlockSize,
            sgbmP1, sgbmP2, sgbmDispMaxDiff, sgbmPreFilterCap, sgbmUniquenessRatio, sgbmSpeckleSize,
            sgbmSpeckleRange, sgbmMode)
//    val stereoBM2: StereoSGBM = StereoSGBM.create(sgbmMinDisparity, sgbmNumDisparities, sgbmBlockSize,
//            sgbmP1, sgbmP2, sgbmDispMaxDiff, sgbmPreFilterCap, sgbmUniquenessRatio, sgbmSpeckleSize,
//            sgbmSpeckleRange, sgbmMode)

    val stereoMatcher: StereoMatcher = createRightMatcher(stereoBM)

//    val stereoBM: StereoBM = StereoBM.create()
//    val stereoBM2: StereoBM = StereoBM.create()

    if (PrefHelper.getInvertFilter(activity)) {
        stereoBM.compute(rotatedNormalMat, rotatedWideMat, disparityMat2)
        stereoMatcher.compute(rotatedWideMat, rotatedNormalMat, disparityMat)
    } else {
        stereoBM.compute(rotatedNormalMat, rotatedWideMat, disparityMat)
        stereoMatcher.compute(rotatedWideMat, rotatedNormalMat, disparityMat2)
    }

    val normalizedDisparityMat1: Mat = Mat()
    val normalizedDisparityMat2: Mat = Mat()

    normalize(disparityMat, normalizedDisparityMat1, 0.0, 255.0, NORM_MINMAX, CV_8U)
    normalize(disparityMat2, normalizedDisparityMat2, 0.0, 255.0, NORM_MINMAX, CV_8U)

    val disparityMatConverted1: Mat = Mat()
    val disparityMatConverted2: Mat = Mat()

    val disparityBitmap: Bitmap = Bitmap.createBitmap(disparityMat.cols(), disparityMat.rows(), Bitmap.Config.ARGB_8888)
    val disparityBitmap2: Bitmap = Bitmap.createBitmap(disparityMat2.cols(), disparityMat2.rows(), Bitmap.Config.ARGB_8888)

//    disparityMat.convertTo(disparityMatConverted1, CV_8UC1, 255 / (sgbmNumDisparities * 16.0));
//    disparityMat2.convertTo(disparityMatConverted2, CV_8UC1, 255 / (sgbmNumDisparities * 16.0));
    normalizedDisparityMat1.convertTo(disparityMatConverted1, CV_8UC1, 1.0 );
    normalizedDisparityMat2.convertTo(disparityMatConverted2, CV_8UC1, 1.0);
    Utils.matToBitmap(disparityMatConverted1, disparityBitmap)
    Utils.matToBitmap(disparityMatConverted2, disparityBitmap2)

    if (PrefHelper.getIntermediate(activity)) {
        activity.runOnUiThread {
            activity.imageIntermediate1.setImageBitmap(rotateBitmap(disparityBitmap,180f))
            activity.imageIntermediate2.setImageBitmap(rotateBitmap(disparityBitmap2, 180f))
        }
    }
    if (PrefHelper.getSaveIntermediate(activity)) {
        WriteFile(activity, rotateBitmap(disparityBitmap,180f), "DisparityMap")
        WriteFile(activity, rotateBitmap(disparityBitmap2,180f), "DisparityMap2")
    }

    val disparityMatFiltered: Mat = Mat(rotatedNormalMat.rows(), rotatedNormalMat.cols(), CV_8UC1)
    val disparityWLSFilter = createDisparityWLSFilter(stereoBM)
    disparityWLSFilter.lambda = PrefHelper.getLambda(activity)
    disparityWLSFilter.sigmaColor = PrefHelper.getSigma(activity)
    disparityWLSFilter.filter(disparityMat, rotatedNormalMat, disparityMatFiltered, disparityMat2, Rect(0, 0, disparityMatConverted1.cols(), disparityMatConverted1.rows()), rotatedWideMat)

    var disparityMapFilteredNormalized: Mat = Mat(disparityMatFiltered.rows(), disparityMatFiltered.cols(), CV_8UC1)
    disparityMapFilteredNormalized = disparityMatFiltered
    normalize(disparityMatFiltered, disparityMapFilteredNormalized, 0.0, 255.0, NORM_MINMAX, CV_8U)
//    disparityMatFiltered.convertTo(disparityMapFilteredNormalized, CV_8UC1, 3 / 16.0)


/*var filteredValues = ""
    var counter = 0
for (row in 0 until disparityMapFilteredNormalized.rows()) {
    for (col in 0 until disparityMapFilteredNormalized.cols()) {
        if ( 0.0 == disparityMapFilteredNormalized.get(row, col)[0])
            continue
        counter++
        filteredValues += "" + disparityMapFilteredNormalized.get(row, col)[0] + ", "
        if (600 < counter)
            break
    }
    if (600 < counter)
        break
}
    Logd("Normalized Filtered Disparity Map Values " + filteredValues )
*/
    val disparityMatFilteredConverted: Mat = Mat(disparityMapFilteredNormalized.rows(), disparityMapFilteredNormalized.cols(), CV_8UC1)
    disparityMapFilteredNormalized.convertTo(disparityMatFilteredConverted, CV_8UC1)

    val disparityBitmapFiltered: Bitmap = Bitmap.createBitmap(disparityMatFilteredConverted.cols(), disparityMatFilteredConverted.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(disparityMatFilteredConverted, disparityBitmapFiltered)
    val disparityBitmapFilteredFinal = rotateBitmap(disparityBitmapFiltered, 180f)

    if (PrefHelper.getSaveIntermediate(activity)) {
        WriteFile(activity, disparityBitmapFilteredFinal, "DisparityMapFilteredNormalized")
    }
    if (PrefHelper.getIntermediate(activity)) {
        activity.runOnUiThread {
            activity.imageIntermediate3.setImageBitmap(disparityBitmapFilteredFinal)
        }
    }

    val normalizedMaskBitmap = Bitmap.createBitmap(disparityMatFilteredConverted.cols(), disparityMatFilteredConverted.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(disparityMatFilteredConverted, normalizedMaskBitmap)

    val hardNormalizedMaskBitmap = hardNormalizeDepthMap(activity, normalizedMaskBitmap)

    if (PrefHelper.getIntermediate(activity)) {
        //Lay it on a black background
        val black = Bitmap.createBitmap(hardNormalizedMaskBitmap.width, hardNormalizedMaskBitmap.height, Bitmap.Config.ARGB_8888)
        val blackCanvas = Canvas(black)
        val paint = Paint()
        paint.setColor(Color.BLACK)
        blackCanvas.drawRect(0f, 0f, hardNormalizedMaskBitmap.width.toFloat(), hardNormalizedMaskBitmap.height.toFloat(), paint)
        activity.runOnUiThread {
            activity.imageIntermediate4.setImageBitmap(pasteBitmap(activity, black, rotateBitmap(hardNormalizedMaskBitmap,180f)))
        }
    }
    if (PrefHelper.getSaveIntermediate(activity)) {
        WriteFile(activity, rotateBitmap(hardNormalizedMaskBitmap,180f), "HardMask")
    }


/*    val rotatedOutputBitmap: Bitmap = Bitmap.createBitmap(rotatedNormalMat.cols(), rotatedNormalMat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rotatedNormalMat, rotatedOutputBitmap)

    val nicelyMasked = applyMask(activity, rotatedOutputBitmap, hardNormalizedMaskBitmap)
    WriteFile(activity, nicelyMasked, "NicelyMasked")
*/
    val smallNormalBitmap = scaleBitmap(activity, tempNormalBitmap, depthMapScaleFactor)
    var rotatedSmallNormalBitmap = rotateBitmap(smallNormalBitmap, 90f)
    rotatedSmallNormalBitmap = horizontalFlip(rotatedSmallNormalBitmap) //Not sure why this is flipped, need to check
    val nicelyMaskedColour = applyMask(activity, rotatedSmallNormalBitmap, hardNormalizedMaskBitmap)

    if (PrefHelper.getSaveIntermediate(activity)) {
        WriteFile(activity, nicelyMaskedColour, "NicelyMaskedColour")
    }

    var backgroundBitmap = Bitmap.createBitmap(rotatedSmallNormalBitmap)

    if (PrefHelper.getSepia(activity))
        backgroundBitmap = sepiaFilter(activity, rotatedSmallNormalBitmap)
    else
        backgroundBitmap = monoBitmap(rotatedSmallNormalBitmap)

    backgroundBitmap = CVBlur(backgroundBitmap)
//    backgroundBitmap = gaussianBlur(activity, backgroundBitmap, BLUR_SCALE_FACTOR)
//    backgroundBitmap = gaussianBlur(activity, backgroundBitmap, BLUR_SCALE_FACTOR)

    if (PrefHelper.getSaveIntermediate(activity)) {
        WriteFile(activity, backgroundBitmap, "Background")
    }

    val finalImage = pasteBitmap(activity, backgroundBitmap, nicelyMaskedColour, android.graphics.Rect(0, 0, backgroundBitmap.width, backgroundBitmap.height))

    if (PrefHelper.getSaveIntermediate(activity)) {
        WriteFile(activity, finalImage, "FinalImage")
    }

    //Free everything we can
    normalMat.release()
    wideMat.release()
    finalNormalMat.release()
    finalWideMat.release()
    rotatedNormalMat.release()
    rotatedWideMat.release()
    resizedNormalMat.release()
    resizedWideMat.release()
    disparityMat.release()
    disparityMat2.release()
    disparityMatConverted1.release()
    disparityMatConverted2.release()
    disparityMapFilteredNormalized.release()
    disparityMatFiltered.release()
    disparityMatFilteredConverted.release()

    return rotateBitmap(finalImage, 180f)
}

fun floatArraytoDoubleArray(fArray: FloatArray) : DoubleArray {
    val dArray: DoubleArray = DoubleArray(fArray.size)
//    Logd("floatArraytoDouble: START")

    var output = ""
    for ((index, float) in fArray.withIndex()) {
        dArray.set(index, float.toDouble())
        output += "" + float.toDouble() + ", "
    }
//    Logd(output)
//    Logd("floatArraytoDouble: END")
    return dArray
}

//From https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_POSE_ROTATION
//For (x,y,x,w)
//R = [ 1 - 2y^2 - 2z^2,       2xy - 2zw,       2xz + 2yw,
//2xy + 2zw, 1 - 2x^2 - 2z^2,       2yz - 2xw,
//2xz - 2yw,       2yz + 2xw, 1 - 2x^2 - 2y^2 ]
fun rotationMatrixFromQuaternion(quatFloat: FloatArray) : DoubleArray {
    val quat: DoubleArray = floatArraytoDoubleArray(quatFloat)
    val rotationMatrix: DoubleArray = DoubleArray(9)

    val x: Int = 0
    val y: Int = 1
    val z: Int = 2
    val w: Int = 3

    //Row 1
    rotationMatrix[0] = 1 - (2 * quat[y] * quat[y]) - (2 * quat[z] * quat[z])
    rotationMatrix[1] = (2 * quat[x] * quat[y]) - (2 * quat[z] * quat[w])
    rotationMatrix[2] = (2 * quat[x] * quat[z]) + (2 * quat[y] * quat[w])

    //Row 2
    rotationMatrix[3] = (2 * quat[x] * quat[y]) + (2 * quat[z] * quat[w])
    rotationMatrix[4] = 1 - (2 * quat[x] * quat[x]) - (2 * quat[z] * quat[z])
    rotationMatrix[5] = (2 * quat[y] * quat[z]) - (2 * quat[x] * quat[w])

    //Row 3
    rotationMatrix[6] = (2 * quat[x] * quat[z]) - (2 * quat[y] * quat[w])
    rotationMatrix[7] = (2 * quat[y] * quat[z]) + (2 * quat[x] * quat[w])
    rotationMatrix[8] = 1 - (2 * quat[x] * quat[x]) - (2 * quat[y] * quat[y])

    //Print
    Logd("Final Rotation Matrix: "
            + rotationMatrix[0] + ", "
            + rotationMatrix[1] + ", "
            + rotationMatrix[2] + ", "
            + rotationMatrix[3] + ", "
            + rotationMatrix[4] + ", "
            + rotationMatrix[5] + ", "
            + rotationMatrix[6] + ", "
            + rotationMatrix[7] + ", "
            + rotationMatrix[8]
    )


    return rotationMatrix
}

//https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_INTRINSIC_CALIBRATION
//[f_x, f_y, c_x, c_y, s]
//K = [ f_x,   s, c_x,
//0, f_y, c_y,
//0    0,   1 ]
 fun cameraMatrixFromCalibration(calibrationFloat: FloatArray) : DoubleArray {
    val cal: DoubleArray = floatArraytoDoubleArray(calibrationFloat)
    val cameraMatrix: DoubleArray = DoubleArray(9)

    val f_x: Int = 0
    val f_y: Int = 1
    val c_x: Int = 2
    val c_y: Int = 3
    val s: Int = 4

    //Row 1
    cameraMatrix[0] = cal[f_x]
    cameraMatrix[1] = cal[s]
    cameraMatrix[2] = cal[c_x]

    //Row 2
    cameraMatrix[3] = 0.0
    cameraMatrix[4] = cal[f_y]
    cameraMatrix[5] = cal[c_y]

    //Row 3
    cameraMatrix[6] = 0.0
    cameraMatrix[7] = 0.0
    cameraMatrix[8] = 1.0

    //Print
    Logd("Final Cam Matrix: "
            + cameraMatrix[0] + ", "
            + cameraMatrix[1] + ", "
            + cameraMatrix[2] + ", "
            + cameraMatrix[3] + ", "
            + cameraMatrix[4] + ", "
            + cameraMatrix[5] + ", "
            + cameraMatrix[6] + ", "
            + cameraMatrix[7] + ", "
            + cameraMatrix[8]
    )

//    printArray(cameraMatrix)

    return cameraMatrix
}

//The android intrinsic values are swizzled from what OpenCV needs. Output indexs should be: 0,1,3,4,2
fun cameraDistortionFromCalibration(calibrationFloat: FloatArray) : DoubleArray {
    val cal: DoubleArray = floatArraytoDoubleArray(calibrationFloat)
    val cameraDistortion: DoubleArray = DoubleArray(5)

    cameraDistortion[0] = cal[0]
    cameraDistortion[1] = cal[1]
    cameraDistortion[2] = cal[3]
    cameraDistortion[3] = cal[4]
    cameraDistortion[4] = cal[2]

    //Print
    Logd("Final Distortion Matrix: "
            + cameraDistortion[0] + ", "
            + cameraDistortion[1] + ", "
            + cameraDistortion[2] + ", "
            + cameraDistortion[3] + ", "
            + cameraDistortion[4]
    )


    return cameraDistortion
}

fun setMat(mat: Mat, rows: Int, cols: Int, vals: DoubleArray) {
    mat.put(0,0, *vals)
/*
    for (row in 0..rows-1) {
        for (col in 0..cols-1) {
            //For some reason, Mat allocation fails for the 5th element sometimes...
            var temp: DoubleArray? = mat[row, col]
            if (null == temp) {
                Logd("Weird, at " + row + "and " + col + " and array is null.")
                continue
            }
            Logd("Checking mat at r: " + row + " and col: " + col + " : " + mat.get(row, col)[0])
        }
    }
*/
}

fun printArray(doubleArray: DoubleArray) {
    Logd("Checking double array Start")
    for (double in doubleArray)
        Logd("element: " + double)
    Logd("Checking double array End")
}