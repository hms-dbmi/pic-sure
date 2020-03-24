define(["text!styles.css", "text!bootstrapStyles", "text!overrides/styles.css", "jquery"], 
		function(styles, bootstrapStyles, overrides, $){
	$('head').append("<style></style>");
	$('head style').html(
			bootstrapStyles.replace(new RegExp('\.\./fonts/', 'g'),	'webjars/bootstrap/3.3.7-1/fonts/')
			+ styles + overrides);
});
