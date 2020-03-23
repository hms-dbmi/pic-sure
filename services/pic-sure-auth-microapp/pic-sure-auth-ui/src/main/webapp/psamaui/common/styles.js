define(["text!styles.css", "text!overrides/styles.css", "jquery"], 
		function(styles, overrides, $){
	$('head').append("<style></style>");
	$('head style').html( styles + overrides);
});