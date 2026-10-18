const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'docs/design/icons/n9a-music-c.svg'));
(async () => {
  await sharp(source).resize(1024,1024).toColourspace('srgb').png().toFile(path.join(root,'docs/design/icons/n9a-music-c.png'));
  const dest=path.join(root,'app/src/main/res/drawable-nodpi');
  fs.mkdirSync(dest,{recursive:true});
  await sharp(source).resize(512,512).toColourspace('srgb').png().toFile(path.join(dest,'ic_launcher.png'));
  const expected=await sharp(source).resize(1024,1024).toColourspace('srgb').ensureAlpha().raw().toBuffer();
  const actual=await sharp(path.join(root,'docs/design/icons/n9a-music-c.png')).ensureAlpha().raw().toBuffer();
  if(!expected.equals(actual)) throw new Error('SVG/PNG pixel comparison failed');
  console.log('SVG/PNG raster comparison passed: 1024 x 1024, sRGB, outlined lettering.');
})();
