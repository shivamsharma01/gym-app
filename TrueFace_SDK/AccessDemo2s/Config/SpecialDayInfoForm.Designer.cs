namespace AccessDemo2s
{
    partial class SpecialDayInfoForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.txt_CustomName = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.label19 = new System.Windows.Forms.Label();
            this.label18 = new System.Windows.Forms.Label();
            this.endtime = new System.Windows.Forms.DateTimePicker();
            this.starttime = new System.Windows.Forms.DateTimePicker();
            this.btn_Confirm = new System.Windows.Forms.Button();
            this.SuspendLayout();
            // 
            // txt_CustomName
            // 
            this.txt_CustomName.Location = new System.Drawing.Point(214, 30);
            this.txt_CustomName.Margin = new System.Windows.Forms.Padding(4);
            this.txt_CustomName.MaxLength = 3000;
            this.txt_CustomName.Name = "txt_CustomName";
            this.txt_CustomName.Size = new System.Drawing.Size(208, 23);
            this.txt_CustomName.TabIndex = 106;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(38, 33);
            this.label2.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(168, 14);
            this.label2.TabIndex = 105;
            this.label2.Text = "CustomName():";
            // 
            // label19
            // 
            this.label19.AutoSize = true;
            this.label19.Location = new System.Drawing.Point(73, 176);
            this.label19.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label19.Name = "label19";
            this.label19.Size = new System.Drawing.Size(133, 14);
            this.label19.TabIndex = 104;
            this.label19.Text = "EndTime():";
            this.label19.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // label18
            // 
            this.label18.AutoSize = true;
            this.label18.Location = new System.Drawing.Point(59, 106);
            this.label18.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label18.Name = "label18";
            this.label18.Size = new System.Drawing.Size(147, 14);
            this.label18.TabIndex = 103;
            this.label18.Text = "StartTime():";
            this.label18.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // endtime
            // 
            this.endtime.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime.Format = System.Windows.Forms.DateTimePickerFormat.Custom;
            this.endtime.Location = new System.Drawing.Point(214, 170);
            this.endtime.Name = "endtime";
            this.endtime.ShowUpDown = true;
            this.endtime.Size = new System.Drawing.Size(208, 23);
            this.endtime.TabIndex = 102;
            this.endtime.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime
            // 
            this.starttime.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime.Format = System.Windows.Forms.DateTimePickerFormat.Custom;
            this.starttime.Location = new System.Drawing.Point(214, 100);
            this.starttime.Name = "starttime";
            this.starttime.ShowUpDown = true;
            this.starttime.Size = new System.Drawing.Size(208, 23);
            this.starttime.TabIndex = 101;
            this.starttime.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // btn_Confirm
            // 
            this.btn_Confirm.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Confirm.Location = new System.Drawing.Point(214, 235);
            this.btn_Confirm.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Confirm.Name = "btn_Confirm";
            this.btn_Confirm.Size = new System.Drawing.Size(140, 24);
            this.btn_Confirm.TabIndex = 107;
            this.btn_Confirm.Text = "Confirm()";
            this.btn_Confirm.UseVisualStyleBackColor = true;
            this.btn_Confirm.Click += new System.EventHandler(this.btn_Confirm_Click);
            // 
            // SpecialDayInfoForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(468, 297);
            this.Controls.Add(this.btn_Confirm);
            this.Controls.Add(this.txt_CustomName);
            this.Controls.Add(this.label2);
            this.Controls.Add(this.label19);
            this.Controls.Add(this.label18);
            this.Controls.Add(this.endtime);
            this.Controls.Add(this.starttime);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "SpecialDayInfoForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "SpecialDayInfo()";
            this.Load += new System.EventHandler(this.SpecialDayInfoForm_Load);
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.TextBox txt_CustomName;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.Label label19;
        private System.Windows.Forms.Label label18;
        private System.Windows.Forms.DateTimePicker endtime;
        private System.Windows.Forms.DateTimePicker starttime;
        private System.Windows.Forms.Button btn_Confirm;
    }
}