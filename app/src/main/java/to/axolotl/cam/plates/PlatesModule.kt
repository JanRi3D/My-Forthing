package to.axolotl.cam.plates

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import to.axolotl.cam.core.data.AppDatabase

@Module
@InstallIn(SingletonComponent::class)
object PlatesModule {
    @Provides
    fun plateDao(db: AppDatabase): PlateDao = db.plateDao()

    /** A new recognizer per injection; the user closes it (LivePlateProcessor.close, ClipPlateScanner per scan). */
    @Provides
    fun plateRecognizer(): PlateRecognizer = MlKitPlateRecognizer()
}
