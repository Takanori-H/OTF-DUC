package MTSAEnactment.ar.uba.dc.lafhis.enactment.robot.ARDrone.V1;

import java.awt.image.BufferedImage;

import de.yadrone.base.video.ImageListener;

public class BarcodeListener implements ImageListener {

	private String resultText = "NONE";

	@Override
	public void imageUpdated(BufferedImage image) {
		/*
		LuminanceSource source = new BufferedImageLuminanceSource(image);
		BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

		// decode the barcode (if only QR codes are used, the QRCodeReader might be a better choice)
		MultiFormatReader reader = new MultiFormatReader();
		
		try {
			Result result = reader.decode(bitmap);
			if(result != null){
				resultText = result.getText();
			}else{
				resultText = "NONE";
			}
			
		} catch (NotFoundException e) {
			//e.printStackTrace();
			resultText = "NONE";
		}
		*/
	}
	
	public String getResult(){
		return resultText;
	}	
}
